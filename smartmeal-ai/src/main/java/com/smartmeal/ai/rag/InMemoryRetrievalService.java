package com.smartmeal.ai.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartmeal.domain.entity.Ingredient;
import com.smartmeal.domain.entity.Recipe;
import com.smartmeal.domain.entity.RecipeIngredient;
import com.smartmeal.repository.mapper.IngredientMapper;
import com.smartmeal.repository.mapper.RecipeIngredientMapper;
import com.smartmeal.repository.mapper.RecipeMapper;
import com.smartmeal.service.user.bo.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于关键词打分的检索实现。
 *
 * <p><b>这是一个有意为之的过渡方案，不是最终形态。</b>
 *
 * <p>为什么先这么做：真正的向量检索需要 Embedding 模型（本机没有装 pgvector，
 * DeepSeek 也不提供 Embedding 接口）。但 RAG 链路的骨架、过滤规则、
 * Prompt 注入格式这些「真正难的部分」跟向量库无关，可以先跑通并验证。
 * 等接上 pgvector 时，只要把 {@code score()} 换成余弦相似度即可，其余代码一行不改。
 *
 * <p>打分用的是「关键词命中 + 结构化加权」：
 * 菜谱名命中、食材命中、目标匹配各给不同权重，比纯 TF 更贴近业务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnMissingBean(name = "pgVectorRetrievalService")
public class InMemoryRetrievalService implements RetrievalService {

    private final RecipeMapper recipeMapper;
    private final IngredientMapper ingredientMapper;
    private final RecipeIngredientMapper recipeIngredientMapper;

    @Override
    public List<KnowledgeChunk> retrieve(UserProfile profile, int topK) {
        Set<Long> allergenIds = profile.allergenIngredientIds();
        Set<String> fridgeNames = new LinkedHashSet<>(profile.getFridgeIngredients());

        List<KnowledgeChunk> candidates = new ArrayList<>();
        candidates.addAll(loadIngredientChunks(profile, allergenIds));
        candidates.addAll(loadRecipeChunks(profile, allergenIds, fridgeNames));

        List<KnowledgeChunk> top = candidates.stream()
                .filter(chunk -> chunk.getScore() > 0)
                .sorted(Comparator.comparingDouble(KnowledgeChunk::getScore).reversed())
                .limit(topK)
                .toList();

        log.info("检索完成 候选={} 命中={} 过敏原过滤={} 冰箱食材={}",
                candidates.size(), top.size(), allergenIds.size(), fridgeNames);
        return top;
    }

    @Override
    public String backend() {
        return "in-memory-keyword";
    }

    /** 食材片段：营养数据 + 是否适合当前目标。 */
    private List<KnowledgeChunk> loadIngredientChunks(UserProfile profile, Set<Long> allergenIds) {
        List<Ingredient> ingredients = ingredientMapper.selectList(
                Wrappers.<Ingredient>lambdaQuery().eq(Ingredient::getStatus, 1));

        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            // 过敏原食材直接不进候选集 —— 这是防线的第一层
            if (allergenIds.contains(ingredient.getId())) {
                continue;
            }
            double score = 0.4;
            if (ingredient.getSuitableGoals() != null && profile.getGoal() != null
                    && ingredient.getSuitableGoals().contains(profile.getGoal())) {
                score += 0.4;
            }
            if (profile.getFridgeIngredients().contains(ingredient.getName())) {
                score += 0.3;
            }

            String content = String.format(
                    "%s：每100g 热量%s千卡，蛋白质%sg，脂肪%sg，碳水%sg。",
                    ingredient.getName(), nz(ingredient.getCaloriesPer100g()),
                    nz(ingredient.getProteinPer100g()), nz(ingredient.getFatPer100g()),
                    nz(ingredient.getCarbPer100g()));

            chunks.add(new KnowledgeChunk("INGREDIENT_" + ingredient.getId(), "INGREDIENT",
                    ingredient.getName(), content, score, ingredient.getId()));
        }
        return chunks;
    }

    /** 菜谱片段：菜谱组成 + 过敏原过滤。 */
    private List<KnowledgeChunk> loadRecipeChunks(UserProfile profile, Set<Long> allergenIds,
                                                  Set<String> fridgeNames) {
        List<Recipe> recipes = recipeMapper.selectList(
                Wrappers.<Recipe>lambdaQuery().eq(Recipe::getStatus, 1));
        if (recipes.isEmpty()) {
            return List.of();
        }

        List<Long> recipeIds = recipes.stream().map(Recipe::getId).toList();
        List<RecipeIngredient> relations = recipeIngredientMapper.selectList(
                Wrappers.<RecipeIngredient>lambdaQuery().in(RecipeIngredient::getRecipeId, recipeIds));

        List<Long> ingredientIds = relations.stream()
                .map(RecipeIngredient::getIngredientId).distinct().toList();
        java.util.Map<Long, String> ingredientNames = ingredientIds.isEmpty()
                ? java.util.Map.of()
                : ingredientMapper.selectBatchIds(ingredientIds).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Ingredient::getId, Ingredient::getName, (a, b) -> a));

        java.util.Map<Long, List<RecipeIngredient>> byRecipe = relations.stream()
                .collect(java.util.stream.Collectors.groupingBy(RecipeIngredient::getRecipeId));

        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (Recipe recipe : recipes) {
            List<RecipeIngredient> items = byRecipe.getOrDefault(recipe.getId(), List.of());

            // 命中过敏原的整道菜直接剔除 —— 第二层防线，比事后校验更省成本
            boolean hitAllergen = items.stream()
                    .anyMatch(item -> allergenIds.contains(item.getIngredientId()));
            if (hitAllergen) {
                continue;
            }
            // 菜谱自身的 allergen_tags 也过一遍
            if (recipe.getAllergenTags() != null && !recipe.getAllergenTags().isBlank()
                    && profile.getAllergenCodes().stream()
                            .anyMatch(code -> recipe.getAllergenTags().contains(code))) {
                continue;
            }

            Set<String> names = new HashSet<>();
            for (RecipeIngredient item : items) {
                String name = ingredientNames.get(item.getIngredientId());
                if (name != null) {
                    names.add(name);
                }
            }

            double score = 0.5;
            if (profile.getMealsPerDay().contains(recipe.getMealType())) {
                score += 0.2;
            }
            if (profile.getGoal() != null && recipe.getTags() != null
                    && recipe.getTags().contains(profile.getGoal())) {
                score += 0.4;
            }
            long fridgeHits = names.stream().filter(fridgeNames::contains).count();
            score += Math.min(fridgeHits * 0.25, 0.5);
            if (recipe.getCookTime() != null && recipe.getCookTime() <= 20) {
                score += 0.1;
            }

            String content = String.format("%s：适合%s，主要食材为%s，烹饪约%s分钟，热量%s千卡。",
                    recipe.getName(), mealLabel(recipe.getMealType()),
                    String.join("、", names), recipe.getCookTime(), nz(recipe.getCalories()));

            chunks.add(new KnowledgeChunk("RECIPE_" + recipe.getId(), "RECIPE",
                    recipe.getName(), content, score, null));
        }
        return chunks;
    }

    private String mealLabel(String mealType) {
        return switch (mealType == null ? "" : mealType) {
            case "breakfast" -> "早餐";
            case "lunch" -> "午餐";
            case "dinner" -> "晚餐";
            case "snack" -> "加餐";
            default -> "任意餐次";
        };
    }

    private String nz(Object value) {
        return value == null ? "-" : value.toString();
    }
}
