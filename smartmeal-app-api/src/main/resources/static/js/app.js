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
        shoppingByPlan: planId => '/api/app/shopping-list/by-plan/' + planId
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

    async function getJson(url) {
        const resp = await fetch(url);
        const body = await resp.json();
        if (body.code !== 200) {
            throw new Error(body.message || ('请求失败（' + body.code + '）'));
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

    // ==================== 初始化 ====================

    async function init() {
        // 自检和档案互不依赖，并行拉取
        loadAiStatus();
        try {
            await loadProfile();
        } catch (e) {
            showAlert('健康档案加载失败：' + e.message);
        }
        try {
            await loadLatestPlan();
        } catch (e) {
            // 没有历史计划属于正常情况（首次访问），静默即可
            console.info('无历史计划：', e.message);
        }
        $('generateForm').addEventListener('submit', onSubmit);
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
            headers: {'Content-Type': 'application/json', 'Accept': 'text/event-stream'},
            body: JSON.stringify(payload)
        });

        if (!resp.ok) {
            // 参数校验失败时后端返回的是普通 JSON，不是事件流
            const text = await resp.text();
            throw new Error('HTTP ' + resp.status + ' ' + text.slice(0, 200));
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
            showAlert('计划已生成，但读取详情失败：' + e.message);
        }
    }

    document.addEventListener('DOMContentLoaded', init);
})();
