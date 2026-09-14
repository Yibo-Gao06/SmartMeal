package com.smartmeal.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartmeal.common.result.PageResult;
import com.smartmeal.common.result.Result;
import com.smartmeal.domain.entity.Ingredient;
import com.smartmeal.repository.mapper.IngredientMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 食材管理。
 *
 * <p>注意食材表里的 {@code allergenTags} 字段：它是过敏原安全链路的源头数据。
 * 运营新增食材时必须正确填写，否则后端校验会失效 —— 这是「数据质量决定系统上限」的典型例子。
 */
@RestController
@RequestMapping("/api/admin/ingredients")
@RequiredArgsConstructor
@Tag(name = "食材管理", description = "食材与营养数据维护")
public class AdminIngredientController {

    private final IngredientMapper ingredientMapper;

    @GetMapping
    @Operation(summary = "分页查询食材")
    public Result<PageResult<Ingredient>> page(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "10") long pageSize,
                                               @RequestParam(required = false) String keyword) {
        Page<Ingredient> page = new Page<>(pageNum, pageSize);
        IPage<Ingredient> result = ingredientMapper.selectPage(page,
                Wrappers.<Ingredient>lambdaQuery()
                        .like(keyword != null && !keyword.isBlank(), Ingredient::getName, keyword)
                        .orderByDesc(Ingredient::getId));
        return Result.success(new PageResult<>(result.getRecords(), result.getTotal(),
                result.getCurrent(), result.getSize()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询食材详情")
    public Result<Ingredient> detail(@PathVariable Long id) {
        return Result.success(ingredientMapper.selectById(id));
    }

    @PostMapping
    @Operation(summary = "新增食材",
            description = "注意 allergenTags 必须正确填写，它直接决定过敏原校验是否有效")
    public Result<Long> create(@RequestBody Ingredient ingredient) {
        ingredientMapper.insert(ingredient);
        return Result.success(ingredient.getId());
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改食材")
    public Result<Void> update(@PathVariable Long id, @RequestBody Ingredient ingredient) {
        ingredient.setId(id);
        ingredientMapper.updateById(ingredient);
        return Result.success();
    }
}
