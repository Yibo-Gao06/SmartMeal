/**
 * SmartMeal 演示页逻辑。
 *
 * 三个要点：
 *
 * 1. **SSE 必须用 fetch + ReadableStream 手动解析**。浏览器的原生 `EventSource`
 *    只支持 GET，而生成接口是 POST —— 请求体有十余个字段且含身体数据，
 *    塞进 URL 既难看又有长度和隐私风险。
 *
 * 2. **事件流按空行（`\n\n`）切块**，每块里有 `event:` 和 `data:` 两行。
 *    `data:` 后面的内容是本项目的 JSON，单行，直接 parse 即可。
 *
 * 3. **收到 `result` 事件只是拿到了 id**，计划详情和购物清单要再拉一次接口。
 *    这么做的好处是「刷新页面能恢复」—— 页面初始化的路径和生成结束的路径
 *    走的是同一套渲染函数，不会出现「刚生成完显示得好好的，刷新一下就少了东西」。
 */
(function () {
    'use strict';

    const API = {
        aiStatus: '/api/app/ai/status',
        profile: '/api/app/user/profile',
        stream: '/api/app/ai/meal-plan/stream',
        latestPlan: '/api/app/meal-plan/latest',
        shoppingByPlan: planId => '/api/app/shopping-list/by-plan/' + planId,
        cartBatch: '/api/app/cart/batch',
        cart: '/api/app/cart',
        cartQuantity: cartId => '/api/app/cart/' + cartId + '/quantity',
        cartSelected: cartId => '/api/app/cart/' + cartId + '/selected',
        cartRemove: cartId => '/api/app/cart/' + cartId,
        orders: '/api/app/orders',
        orderDetail: orderNo => '/api/app/orders/' + orderNo,
        orderPay: orderNo => '/api/app/orders/' + orderNo + '/pay',
        orderCancel: orderNo => '/api/app/orders/' + orderNo + '/cancel',
        login: '/api/app/auth/login',
        logout: '/api/app/auth/logout'
    };

    /** 阶段 → 进度百分比。按真实阶段推进，而不是跑一条假动画。 */
    const STAGE_PROGRESS = {
        INIT: 8,
        RETRIEVE: 22,
        PROMPT: 32,
        GENERATE: 55,
        FALLBACK: 60,
        PARSING: 70,
        VALIDATING: 82,
        SAVING: 90,
        SHOPPING_LIST: 96
    };

    const GOAL_LABEL = {
        loss_fat: '减脂',
        gain_muscle: '增肌',
        balance: '均衡',
        low_sugar: '控糖'
    };

    const ORDER_STATUS_LABEL = {
        PENDING_PAYMENT: ['待支付', 'warn'],
        PAID: ['已支付', 'ok'],
        DELIVERING: ['配送中', 'ok'],
        COMPLETED: ['已完成', 'ok'],
        CANCELLED: ['已取消', 'muted'],
        REFUNDING: ['退款中', 'warn'],
        REFUNDED: ['已退款', 'muted']
    };

    /**
     * 单位的中文显示。
     *
     * 库里存的是英文单位（t_ingredient.unit），这是对的 —— 单位参与换算逻辑，
     * 不该被展示层污染。但直接把 piece 显示给用户就是「4piece」，
     * 所以在这里做一次纯展示的映射。
     *
     * 只映射读起来别扭的：g / ml / kg 是通用符号，中文界面里直接显示反而更清楚，
     * 全部换成「克」「毫升」会让表格变宽、数字更难扫。
     */
    const UNIT_LABEL = {piece: '枚'};

    function unit(u) {
        if (!u) return '';
        return UNIT_LABEL[u] || u;
    }

    const $ = id => document.getElementById(id);

    // ==================== 小工具 ====================

    function num(value, digits) {
        if (value === null || value === undefined || value === '') return '-';
        const n = Number(value);
        if (!isFinite(n)) return '-';
        return digits === undefined ? String(Math.round(n * 100) / 100) : n.toFixed(digits);
    }

    function money(value) {
        if (value === null || value === undefined) return '-';
        return '¥' + Number(value).toFixed(2);
    }

    function esc(text) {
        return String(text === null || text === undefined ? '' : text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
    }

    // ==================== 鉴权（token 持久化 + 登录态） ====================

    /**
     * 登录态存 localStorage：刷新页面不丢会话，演示页不用每次都登录。
     * 只存 token / token 头名 / 用户名这类非敏感展示信息，绝不碰密码 ——
     * 密码只在登录请求里走一次，不落本地。
     */
    const TOKEN_KEY = 'smartmeal_token';
    const TOKEN_NAME_KEY = 'smartmeal_token_name';
    const USER_KEY = 'smartmeal_user';

    /**
     * 未登录（或登录态过期）时由 {@link getJson} / {@link consumeStream} 抛出，
     * 用来和普通的业务错误区分开：普通错误弹提示条，未登录要弹登录框。
     */
    class AuthRequiredError extends Error {
        constructor() {
            super('请先登录');
        }
    }

    /**
     * Sa-Token 拦截未登录请求默认返回 code=11011（HTTP 200 包一层 JSON），
     * 而本项目的 {@code CurrentUserResolver} 在未登录时抛 UNAUTHORIZED=401。
     * 两种都算「需要登录」，统一拦截弹框。
     */
    function isUnauthorized(code, httpStatus) {
        return code === 401 || code === 11011 || httpStatus === 401;
    }

    function getToken() {
        return localStorage.getItem(TOKEN_KEY);
    }

    /** token 头名以登录接口返回的为准（默认 satoken），避免和后端配置漂移。 */
    function tokenHeaderName() {
        return localStorage.getItem(TOKEN_NAME_KEY) || 'satoken';
    }

    function setSession(token, tokenName, user) {
        localStorage.setItem(TOKEN_KEY, token);
        if (tokenName) localStorage.setItem(TOKEN_NAME_KEY, tokenName);
        localStorage.setItem(USER_KEY, JSON.stringify(user || {}));
    }

    function clearSession() {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(TOKEN_NAME_KEY);
        localStorage.removeItem(USER_KEY);
    }

    /** 给 fetch 加上鉴权头。没有 token 时（未登录）就不加，让后端正常返回 401。 */
    function authHeaders(extra) {
        const headers = extra ? Object.assign({}, extra) : {};
        const token = getToken();
        if (token) {
            headers[tokenHeaderName()] = token;
        }
        return headers;
    }

    function onUnauthorized() {
        clearSession();
        updateUserArea();
        showLoginModal();
    }

    async function getJson(url) {
        return sendJson(url, 'GET', null);
    }

    /** 与 getJson 同一套错误约定：HTTP 永远 200，业务成败看 body.code。 */
    async function sendJson(url, method, payload) {
        const options = {
            method: method,
            headers: authHeaders(payload ? {'Content-Type': 'application/json'} : {})
        };
        if (payload) {
            options.body = JSON.stringify(payload);
        }
        const resp = await fetch(url, options);
        let body = null;
        try {
            body = await resp.json();
        } catch (e) {
            body = null;
        }
        if (body && typeof body.code === 'number' && body.code !== 200) {
            if (isUnauthorized(body.code, resp.status)) {
                onUnauthorized();
                throw new AuthRequiredError();
            }
            const message = body.message || body.msg
                || ('请求失败（' + body.code + '）');
            throw new Error(message);
        }
        if (!body) {
            throw new Error('响应解析失败（HTTP ' + resp.status + '）');
        }
        return body.data;
    }

    function showAlert(message, type) {
        const el = $('alert');
        el.textContent = message;
        el.className = 'alert alert-' + (type || 'error');
        el.hidden = false;
    }

    function hideAlert() {
        $('alert').hidden = true;
    }

    function renderChips(el, items, cls, emptyText) {
        if (!items || items.length === 0) {
            el.innerHTML = '<span class="empty">' + esc(emptyText) + '</span>';
            return;
        }
        el.innerHTML = items
            .map(v => '<span class="chip ' + cls + '">' + esc(v) + '</span>')
            .join('');
    }

    // ==================== 登录态 UI ====================

    function showLoginModal() {
        hideLoginError();
        $('loginMask').hidden = false;
        // 演示账号已预填，把焦点放到密码框，回车即可登录
        const pw = $('loginPassword');
        if (pw) pw.focus();
    }

    function hideLoginModal() {
        $('loginMask').hidden = true;
    }

    function showLoginError(message) {
        const el = $('loginError');
        el.textContent = message;
        el.hidden = false;
    }

    function hideLoginError() {
        $('loginError').hidden = true;
    }

    function setLoginLoading(loading) {
        $('loginSubmit').disabled = loading;
        $('loginSubmit').textContent = loading ? '登录中…' : '登录';
    }

    function updateUserArea() {
        const token = getToken();
        if (token) {
            let user = {};
            try {
                user = JSON.parse(localStorage.getItem(USER_KEY)) || {};
            } catch (e) {
                user = {};
            }
            $('userName').textContent = user.nickname || user.username || '已登录';
            $('userArea').hidden = false;
            $('loginBtn').hidden = true;
        } else {
            $('userArea').hidden = true;
            $('loginBtn').hidden = false;
        }
    }

    async function doLogin(username, password) {
        const resp = await fetch(API.login, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({username: username, password: password})
        });
        const body = await resp.json();
        if (body.code !== 200) {
            throw new Error(body.message || body.msg
                || ('登录失败（' + body.code + '）'));
        }
        const data = body.data;
        setSession(data.token, data.tokenName, {
            userId: data.userId,
            username: data.username,
            nickname: data.nickname
        });
        return data;
    }

    async function onLoginSubmit(event) {
        event.preventDefault();
        const username = $('loginUsername').value.trim();
        const password = $('loginPassword').value;
        if (!username || !password) {
            showLoginError('请输入用户名和密码');
            return;
        }
        setLoginLoading(true);
        hideLoginError();
        try {
            await doLogin(username, password);
            hideLoginModal();
            updateUserArea();
            await bootData();
        } catch (err) {
            showLoginError(err.message);
        } finally {
            setLoginLoading(false);
        }
    }

    async function doLogout() {
        try {
            await fetch(API.logout, {method: 'POST', headers: authHeaders()});
        } catch (e) {
            // 登出请求失败不影响本地清态：页面马上要回到登录框
            console.warn('登出请求失败', e);
        }
        clearSession();
        updateUserArea();
        showLoginModal();
    }

    function bindStaticEvents() {
        $('generateForm').addEventListener('submit', onSubmit);
        $('loginForm').addEventListener('submit', onLoginSubmit);
        $('loginBtn').addEventListener('click', showLoginModal);
        $('logoutBtn').addEventListener('click', doLogout);
        $('addToCartBtn').addEventListener('click', onAddToCart);
        $('checkoutBtn').addEventListener('click', onCheckout);
        // 购物车 / 订单的行内控件在 innerHTML 重绘后会丢失监听，统一挂到容器上做事件委托
        $('cartBody').addEventListener('change', onCartChange);
        $('cartBody').addEventListener('click', onCartClick);
        $('orderList').addEventListener('click', onOrderClick);
        // 不绑定「点遮罩关闭」：登录是必经步骤，误关反而让人困惑
    }

    /**
     * 拉首屏数据。token 过期会在各 getJson 内部触发登录框并抛 AuthRequiredError，
     * 这里统一吞掉，不再重复弹提示条。
     */
    async function bootData() {
        loadAiStatus();
        try {
            await loadProfile();
        } catch (e) {
            if (e instanceof AuthRequiredError) return;
            showAlert('健康档案加载失败：' + e.message);
        }
        try {
            await loadLatestPlan();
        } catch (e) {
            if (e instanceof AuthRequiredError) return;
            console.info('无历史计划：', e.message);
        }
        try {
            await loadCart();
            await loadOrders();
        } catch (e) {
            if (e instanceof AuthRequiredError) return;
            console.info('购物车/订单加载失败：', e.message);
        }
    }

    // ==================== 初始化 ====================

    async function init() {
        bindStaticEvents();
        updateUserArea();
        // 没 token 就直接弹登录框，不浪费请求去撞 401
        if (!getToken()) {
            showLoginModal();
            return;
        }
        await bootData();
    }

    async function loadAiStatus() {
        const el = $('aiStatus');
        try {
            const data = await getJson(API.aiStatus);
            const mock = data.mock === true || data.llmClient === 'MockLlmClient';
            el.className = 'status ' + (mock ? 'is-mock' : 'is-live');
            $('aiStatusText').textContent = mock
                ? '未配置 API Key · 演示数据'
                : '已接入真实模型';
        } catch (e) {
            if (e instanceof AuthRequiredError) throw e;
            el.className = 'status';
            $('aiStatusText').textContent = 'AI 状态未知';
        }
    }

    async function loadProfile() {
        const p = await getJson(API.profile);

        $('profileGoal').textContent = GOAL_LABEL[p.goal] || p.goal || '';
        $('m-height').textContent = p.heightCm ? num(p.heightCm) + ' cm' : '-';
        $('m-weight').textContent = p.weightKg ? num(p.weightKg) + ' kg' : '-';
        $('m-bmi').textContent = p.bmi ? num(p.bmi, 1) : '-';
        $('m-bmr').textContent = p.bmr ? num(p.bmr) + ' kcal' : '-';
        $('m-tdee').textContent = p.tdee ? num(p.tdee) + ' kcal' : '-';
        $('m-target').textContent = p.dailyCalorieTarget
            ? num(p.dailyCalorieTarget) + ' kcal' : '-';

        // allergenIngredientNames 是「食材ID → 食材名」的 Map，
        // 展示时只关心名字，这里取 value 即可
        const forbidden = Object.values(p.allergenIngredientNames || {});
        renderChips($('allergenChips'), forbidden, 'chip-danger', '未申报过敏原');

        const fridge = p.fridgeStock || [];
        if (fridge.length === 0) {
            $('fridgeChips').innerHTML = '<span class="empty">无</span>';
        } else {
            $('fridgeChips').innerHTML = fridge
                .map(f => '<span class="chip chip-accent">' + esc(f.name) + ' '
                    + num(f.amount) + unit(f.unit) + '</span>')
                .join('');
        }
    }

    async function loadLatestPlan() {
        const plan = await getJson(API.latestPlan);
        if (!plan) return;
        renderPlan(plan);
        if (plan.status === 'SUCCESS' || plan.status === 'FALLBACK') {
            const list = await getJson(API.shoppingByPlan(plan.planId));
            if (list) renderShopping(list);
        }
    }

    // ==================== 渲染 ====================

    function renderPlan(plan) {
        $('planSection').hidden = false;
        $('planMeta').textContent = [
            plan.planNo,
            plan.goalLabel || GOAL_LABEL[plan.goal] || plan.goal,
            plan.days + ' 天',
            '目标 ' + num(plan.dailyCalorieTarget) + ' kcal',
            '合计 ' + num(plan.totalCalories) + ' kcal'
        ].filter(Boolean).join(' · ');

        const warnings = plan.warnings || [];
        $('planWarnings').innerHTML = warnings
            .map(w => '<div class="warning-item">' + esc(w) + '</div>')
            .join('');

        const days = plan.dayList || [];
        if (days.length === 0) {
            $('planDays').innerHTML = '<p class="empty">该计划还没有明细'
                + (plan.errorMsg ? '：' + esc(plan.errorMsg) : '') + '</p>';
            return;
        }

        $('planDays').innerHTML = days.map(day => {
            const meals = (day.meals || []).map(meal => {
                const ingredients = (meal.ingredients || [])
                    .map(i => esc(i.ingredientName) + ' ' + num(i.amount) + unit(i.unit))
                    .join('、');
                const servings = meal.servings > 1
                    ? '<span class="servings">×' + meal.servings + ' 份</span>' : '';
                return ''
                    + '<div class="meal">'
                    + '  <div class="meal-head">'
                    + '    <span class="meal-type">' + esc(meal.mealTypeLabel) + '</span>'
                    + '    <span class="meal-name">' + esc(meal.recipeName) + '</span>'
                    + servings
                    + '    <span class="meal-cal">' + num(meal.calories) + ' kcal</span>'
                    + '  </div>'
                    + (meal.reason ? '<p class="meal-reason">' + esc(meal.reason) + '</p>' : '')
                    + (ingredients ? '<p class="meal-ing">' + ingredients + '</p>' : '')
                    + '</div>';
            }).join('');

            return ''
                + '<div class="day">'
                + '  <div class="day-head">'
                + '    <span class="day-title">第 ' + day.dayNo + ' 天</span>'
                + '    <span class="day-cal">' + num(day.totalCalories) + ' kcal</span>'
                + '  </div>'
                + meals
                + '</div>';
        }).join('');
    }

    function renderShopping(list) {
        $('shoppingSection').hidden = false;
        $('shoppingMeta').textContent = list.title
            + ' · ' + (list.items || []).length + ' 项';

        const rows = (list.items || []).map(item => {
            const u = unit(item.unit);
            // 冰箱扣减单独用绿色标签标出来，让用户一眼看到「这些不用买」
            const fridge = Number(item.fridgeAmount) > 0
                ? '<span class="tag-fridge">-' + num(item.fridgeAmount) + u + '</span>'
                : '<span class="muted">—</span>';
            const sku = item.skuName
                ? esc(item.skuName)
                : '<span class="muted">未匹配到商品</span>';

            return ''
                + '<tr>'
                + '  <td>' + esc(item.ingredientName) + '</td>'
                + '  <td class="num">' + num(item.requiredAmount) + u + '</td>'
                + '  <td class="num">' + fridge + '</td>'
                + '  <td class="num">' + num(item.needBuyAmount) + u + '</td>'
                + '  <td>' + sku + '</td>'
                + '  <td class="num">' + (item.quantity ? item.quantity : '—') + '</td>'
                + '  <td class="num">' + money(item.subtotal) + '</td>'
                + '</tr>';
        }).join('');

        $('shoppingBody').innerHTML = rows || '<tr><td colspan="7" class="muted">清单为空</td></tr>';
        $('shoppingTotal').textContent = money(list.totalPrice);

        currentShopping = list;
        const addable = (list.items || []).filter(i => i.status === 'AVAILABLE'
            && Number(i.quantity) > 0).length;
        $('addToCartBtn').hidden = addable === 0;
        $('addToCartBtn').disabled = false;
        $('addToCartHint').textContent = addable > 0
            ? addable + ' 项可加购，同 SKU 条目会自动合并' : '';
    }

    // ==================== 交易：加购 / 购物车 / 下单 / 支付 ====================

    let currentShopping = null;

    async function onAddToCart() {
        if (!currentShopping) return;
        const btn = $('addToCartBtn');
        btn.disabled = true;
        btn.textContent = '加购中…';
        try {
            const r = await sendJson(API.cartBatch, 'POST',
                {shoppingListId: currentShopping.listId});
            const parts = ['已加购 ' + r.addedCount + ' 项'];
            if (r.coveredByFridge && r.coveredByFridge.length) {
                parts.push('冰箱已有 ' + r.coveredByFridge.join('、'));
            }
            (r.skipped || []).forEach(s =>
                parts.push('跳过 ' + s.ingredientName + '：' + s.reason));
            showAlert(parts.join('；'), 'info');
            $('addToCartHint').textContent = '已加购 ' + r.addedCount + ' 项';
            await loadCart();
        } catch (e) {
            if (!(e instanceof AuthRequiredError)) {
                showAlert('加购失败：' + e.message);
            }
        } finally {
            btn.disabled = false;
            btn.textContent = '一键加入购物车';
        }
    }

    async function loadCart() {
        const cart = await sendJson(API.cart, 'GET', null);
        renderCart(cart);
    }

    function renderCart(cart) {
        const section = $('cartSection');
        const items = cart.items || [];
        section.hidden = items.length === 0;
        $('cartMeta').textContent = items.length + ' 项 · 已选 ' + cart.selectedCount + ' 项';

        $('cartBody').innerHTML = items.map(it => {
            const bad = !it.available;
            const name = esc(it.productName || it.skuName || ('SKU#' + it.skuId));
            const spec = esc([it.spec, it.skuName].filter(Boolean).join(' · '));
            const flag = bad
                ? '<span class="chip chip-danger">' + esc(it.unavailableReason || '不可购') + '</span>'
                : '';
            return ''
                + '<tr class="' + (bad ? 'row-disabled' : '') + '">'
                + '  <td><input type="checkbox" class="cart-sel" data-id="' + it.cartId
                + '"' + (it.selected && !bad ? ' checked' : '') + (bad ? ' disabled' : '') + '></td>'
                + '  <td>' + name + ' ' + flag + '</td>'
                + '  <td class="muted">' + spec + '</td>'
                + '  <td class="num">' + money(it.price) + '</td>'
                + '  <td class="num"><input type="number" class="cart-qty" data-id="' + it.cartId
                + '" value="' + it.quantity + '" min="1" max="99"></td>'
                + '  <td class="num">' + money(it.subtotal) + '</td>'
                + '  <td><button type="button" class="btn-link cart-del" data-id="' + it.cartId
                + '">删除</button></td>'
                + '</tr>';
        }).join('');

        $('cartTotal').textContent = money(cart.totalAmount);
        const canCheckout = (cart.selectedCount || 0) > 0;
        $('checkoutBtn').hidden = !canCheckout;
        $('checkoutBtn').disabled = false;
        $('cartHint').textContent = canCheckout ? '' : '勾选要购买的条目后即可结算';
    }

    async function onCartChange(event) {
        const el = event.target;
        const id = el.dataset && el.dataset.id;
        if (!id) return;
        try {
            if (el.classList.contains('cart-qty')) {
                await sendJson(API.cartQuantity(id) + '?quantity='
                    + encodeURIComponent(el.value), 'PUT', null);
            } else if (el.classList.contains('cart-sel')) {
                await sendJson(API.cartSelected(id) + '?selected='
                    + (el.checked ? 'true' : 'false'), 'PUT', null);
            } else {
                return;
            }
            await loadCart();
        } catch (e) {
            if (!(e instanceof AuthRequiredError)) {
                showAlert('更新购物车失败：' + e.message);
                await loadCart().catch(() => {});
            }
        }
    }

    async function onCartClick(event) {
        const el = event.target;
        const id = el.dataset && el.dataset.id;
        if (!id) return;
        try {
            if (el.classList.contains('cart-del')) {
                await sendJson(API.cartRemove(id), 'DELETE', null);
                await loadCart();
            }
        } catch (e) {
            if (!(e instanceof AuthRequiredError)) {
                showAlert('删除失败：' + e.message);
            }
        }
    }

    async function onCheckout() {
        const btn = $('checkoutBtn');
        btn.disabled = true;
        btn.textContent = '提交中…';
        try {
            const order = await sendJson(API.orders, 'POST', {remark: '来自一周膳食计划'});
            showAlert('下单成功，订单号 ' + order.orderNo + '，请在 15 分钟内完成支付。', 'info');
            await Promise.all([loadCart(), loadOrders()]);
        } catch (e) {
            if (!(e instanceof AuthRequiredError)) {
                showAlert('下单失败：' + e.message);
                await loadCart().catch(() => {});
            }
        } finally {
            btn.disabled = false;
            btn.textContent = '去结算';
        }
    }

    async function loadOrders() {
        const page = await sendJson(API.orders + '?pageNum=1&pageSize=5', 'GET', null);
        renderOrders(page.records || []);
    }

    function renderOrders(orders) {
        $('orderSection').hidden = orders.length === 0;
        $('orderList').innerHTML = orders.map(o => {
            const label = ORDER_STATUS_LABEL[o.status] || [o.status, 'muted'];
            const actions = [];
            if (o.status === 'PENDING_PAYMENT') {
                actions.push('<button type="button" class="btn-primary btn-sm order-pay" data-no="'
                    + o.orderNo + '">支付（模拟）</button>');
                actions.push('<button type="button" class="btn-link order-cancel" data-no="'
                    + o.orderNo + '">取消</button>');
            }
            actions.push('<button type="button" class="btn-link order-detail" data-no="'
                + o.orderNo + '">明细</button>');
            return ''
                + '<li class="order-row" data-no="' + o.orderNo + '">'
                + '  <div class="order-head">'
                + '    <span class="order-no">' + esc(o.orderNo) + '</span>'
                + '    <span class="badge badge-' + label[1] + '">' + label[0] + '</span>'
                + '    <span class="order-amount">' + money(o.payAmount) + '</span>'
                + '    <span class="hint">' + esc((o.createTime || '').replace('T', ' ')) + '</span>'
                + '  </div>'
                + '  <div class="order-actions">' + actions.join('') + '</div>'
                + '  <div class="order-detail-box" hidden></div>'
                + '</li>';
        }).join('');
    }

    async function onOrderClick(event) {
        const btn = event.target;
        const no = btn.dataset && btn.dataset.no;
        if (!no) return;
        try {
            if (btn.classList.contains('order-pay')) {
                btn.disabled = true;
                await sendJson(API.orderPay(no), 'POST', {payType: 'MOCK'});
                showAlert('支付成功（模拟渠道），订单已进入备货流程。', 'info');
                await Promise.all([loadOrders(), loadCart()]);
            } else if (btn.classList.contains('order-cancel')) {
                await sendJson(API.orderCancel(no), 'POST', null);
                showAlert('订单已取消，库存已回补。', 'info');
                await Promise.all([loadOrders(), loadCart()]);
            } else if (btn.classList.contains('order-detail')) {
                const box = btn.closest('.order-row').querySelector('.order-detail-box');
                if (!box.hidden && box.dataset.loaded) {
                    box.hidden = true;
                    return;
                }
                const d = await sendJson(API.orderDetail(no), 'GET', null);
                box.innerHTML = (d.items || []).map(it =>
                    '<div class="order-line"><span>' + esc(it.productName || it.skuName)
                    + ' × ' + it.quantity + '</span><span>' + money(it.amount) + '</span></div>'
                ).join('')
                    + '<div class="order-line order-line-total"><span>运费</span><span>'
                    + money(d.freightAmount) + '</span></div>';
                box.dataset.loaded = '1';
                box.hidden = false;
            }
        } catch (e) {
            if (!(e instanceof AuthRequiredError)) {
                showAlert('操作失败：' + e.message);
            }
        } finally {
            if (btn.classList.contains('order-pay')) btn.disabled = false;
        }
    }

    // ==================== 生成（SSE） ====================

    function resetStreamUi() {
        $('progressWrap').hidden = false;
        $('progressFill').style.width = '0%';
        $('progressText').textContent = '正在连接…';
        $('streamWrap').hidden = false;
        $('streamOutput').textContent = '';
        $('streamCount').textContent = '0 字符';
        $('generateBtn').disabled = true;
    }

    function finishStreamUi() {
        $('generateBtn').disabled = false;
    }

    function onSubmit(event) {
        event.preventDefault();
        hideAlert();
        resetStreamUi();

        const allergens = $('allergens').value
            .split(',')
            .map(s => s.trim())
            .filter(s => s.length > 0);

        const payload = {
            // requestId 由前端生成：同一逻辑请求复用同一个值，
            // 后端据此实现防重复提交与幂等落库
            requestId: 'web-' + Date.now(),
            goal: $('goal').value,
            days: Number($('days').value) || 3
        };
        if (allergens.length > 0) {
            payload.allergens = allergens;
        }

        consumeStream(payload).catch(err => {
            showAlert('生成失败：' + err.message);
            $('progressText').textContent = '已中断';
            finishStreamUi();
        });
    }

    async function consumeStream(payload) {
        const resp = await fetch(API.stream, {
            method: 'POST',
            headers: authHeaders({
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream'
            }),
            body: JSON.stringify(payload)
        });

        const contentType = resp.headers.get('Content-Type') || '';
        if (!resp.ok || !contentType.includes('text/event-stream')) {
            // 未登录或参数校验失败时，后端返回的是普通 JSON 错误体（不是事件流）：
            // 本项目所有响应统一走 HTTP 200 + body.code，所以即使 resp.ok 也要看 Content-Type。
            // 解析出来看是不是「需要登录」，是就弹登录框，否则按普通错误抛出
            const text = await resp.text();
            const errInfo = safeParse(text) || {};
            if (isUnauthorized(errInfo.code, resp.status)) {
                onUnauthorized();
            }
            const message = (errInfo.message || errInfo.msg
                || ('HTTP ' + resp.status)).toString();
            throw new Error(message);
        }
        if (!resp.body) {
            throw new Error('当前浏览器不支持流式响应');
        }

        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let finalPayload = null;

        while (true) {
            const {done, value} = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, {stream: true});

            // 事件之间用空行分隔，最后一段可能不完整，留在 buffer 里等下一轮
            let index;
            while ((index = buffer.indexOf('\n\n')) >= 0) {
                const block = buffer.slice(0, index);
                buffer = buffer.slice(index + 2);
                const parsed = handleEvent(block);
                if (parsed) finalPayload = parsed;
            }
        }

        finishStreamUi();

        if (finalPayload) {
            await onGenerated(finalPayload);
        }
    }

    /**
     * 处理一个事件块。
     *
     * @return 收到 result 事件时返回其 data，其余事件返回 null
     */
    function handleEvent(block) {
        let event = 'message';
        let data = '';

        for (const line of block.split('\n')) {
            if (line.startsWith('event:')) {
                event = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
                data += line.slice(5).trim();
            }
        }

        switch (event) {
            case 'progress':
                applyProgress(data);
                break;
            case 'token':
                appendToken(data);
                break;
            case 'result':
                $('progressFill').style.width = '98%';
                $('progressText').textContent = '生成完成，正在读取结果…';
                return safeParse(data);
            case 'done':
                $('progressFill').style.width = '100%';
                break;
            case 'error': {
                const err = safeParse(data) || {};
                showAlert(err.message || '生成失败');
                $('progressFill').style.width = '100%';
                $('progressText').textContent = '已终止';
                break;
            }
            case 'heartbeat':
                // 保活事件，无需处理
                break;
            default:
                break;
        }
        return null;
    }

    function applyProgress(data) {
        const info = safeParse(data) || {};
        const percent = STAGE_PROGRESS[info.stage];
        if (typeof percent === 'number') {
            $('progressFill').style.width = percent + '%';
        }
        if (info.message) {
            $('progressText').textContent = info.message;
        }
    }

    function appendToken(data) {
        const info = safeParse(data);
        // SseHelper.sendToken 发的是 Map.of("content", content)，
        // 所以 data 实际长这样：{"content":"{\"days\":["}
        // 解析不出来时（理论上不会）退回原文，宁可多显示也不吞掉内容
        const text = (info && typeof info === 'object' && typeof info.content === 'string')
            ? info.content
            : data;

        const el = $('streamOutput');
        el.textContent += text;
        $('streamCount').textContent = el.textContent.length + ' 字符';
        // 自动滚到底，让用户看到最新的增量
        el.scrollTop = el.scrollHeight;
    }

    function safeParse(text) {
        if (!text) return null;
        try {
            return JSON.parse(text);
        } catch (e) {
            return null;
        }
    }

    /** 生成成功后拉取完整详情 —— 与页面初始化走同一套渲染逻辑。 */
    async function onGenerated(result) {
        try {
            const plan = await getJson('/api/app/meal-plan/' + result.planId);
            renderPlan(plan);

            if (result.shoppingListId) {
                const list = await getJson('/api/app/shopping-list/' + result.shoppingListId);
                renderShopping(list);
            }

            if (result.mock) {
                showAlert('当前未配置大模型 API Key，以上为结构合法的演示数据，'
                    + '营养数值不代表真实建议。', 'info');
            }
            $('progressText').textContent = '完成';
        } catch (e) {
            if (e instanceof AuthRequiredError) return;
            showAlert('计划已生成，但读取详情失败：' + e.message);
        }
    }

    document.addEventListener('DOMContentLoaded', init);
})();
