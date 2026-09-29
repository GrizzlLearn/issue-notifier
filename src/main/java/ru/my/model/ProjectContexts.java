package ru.my.model;

import org.codehaus.jackson.map.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Чтение, запись и резолв контекстов проектов — настройка {@value #KEY}.
 * <p>
 * Все контексты лежат в одном ключе настроек JSON-массивом, а не россыпью
 * ключей {@code ctx.<id>.*}: создание, переименование, удаление и порядок — это
 * запись одного ключа. Поэтому нет полусобранного контекста на соседней ноде
 * Data Center, не бывает мусорных ключей от удалённого контекста, а на событие
 * в задаче уходит одно чтение настроек вместо чтения на каждое поле.
 * <p>
 * JSON разбирается тем же Jackson 1.9, что Jira даёт своему REST-слою
 * (см. {@code org.codehaus.jackson} в DTO) — своей зависимости не добавляется.
 */
public final class ProjectContexts {

    public static final String KEY = "contexts";

    /**
     * Встроенный контекст «Остальные проекты» — задачи, не попавшие ни в один
     * явный контекст. Заменяет прежнюю область «Во всех проектах»: поведение
     * всего инстанса задаётся здесь, а отдельные категории выносятся в свои
     * контексты. Удалить его нельзя, {@link #parse} создаёт его при отсутствии.
     */
    public static final String DEFAULT_ID = "default";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProjectContexts() {
    }

    /**
     * Разбирает значение настройки.
     * <p>
     * Мусор пропускается, как и в остальных настройках: строку пишет только
     * админ-страница, ломать всю рассылку из-за одного битого контекста не за что.
     * Контекст без id отбрасывается — по нему нельзя ни настроить, ни удалить.
     *
     * @param raw значение настройки; может быть null или пустым
     * @return явные контексты в порядке сохранения, последним — {@link #DEFAULT_ID}
     */
    public static List<ProjectContext> parse(String raw) {
        List<ProjectContext> result = new ArrayList<>();
        ProjectContext defaultContext = null;

        for (Object element : readArray(raw)) {
            if (!(element instanceof Map)) {
                continue;
            }
            ProjectContext context = toContext((Map<?, ?>) element);
            if (context == null) {
                continue;
            }
            if (context.isDefault()) {
                defaultContext = context;
            } else {
                result.add(context);
            }
        }

        // «Остальные проекты» существуют всегда: вкладка «Действия» не должна
        // оставаться без единого контекста, а «все проекты, кроме отмеченных»
        // иначе было бы нечем настроить
        result.add(defaultContext != null ? defaultContext : emptyDefault());
        return result;
    }

    /** Значение настройки для списка контекстов; {@link #DEFAULT_ID} сохраняется как обычный. */
    public static String format(List<ProjectContext> contexts) {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (ProjectContext context : contexts) {
            raw.add(toMap(context));
        }
        try {
            return MAPPER.writeValueAsString(raw);
        } catch (IOException e) {
            // структура собрана здесь же из строк, коллекций и boolean — писать нечему падать
            throw new IllegalStateException("Не удалось сохранить контексты проектов", e);
        }
    }

    /**
     * Контекст, по которому работают действия для задачи.
     * <p>
     * Правило — специфичность, без слияния настроек: явно отмеченный проект
     * побеждает категорию, выбранный контекст применяется целиком. Слияние
     * молча включало бы лишние уведомления и делало невыразимым исключение
     * вида «категория целиком, кроме одного проекта».
     * <p>
     * Уникальность проекта и категории между контекстами проверяется при
     * сохранении, но два администратора на разных нодах могут её обойти,
     * поэтому здесь побеждает первый по порядку — результат хотя бы предсказуем.
     *
     * @param contexts   список из {@link #parse}
     * @param projectKey ключ проекта задачи
     * @param categoryId id категории проекта; {@code null} — проект вне категорий
     * @return подходящий контекст; если ни один не подошёл — {@link #DEFAULT_ID}
     */
    public static ProjectContext resolve(List<ProjectContext> contexts, String projectKey, Long categoryId) {
        ProjectContext defaultContext = null;
        for (ProjectContext context : contexts) {
            if (context.isDefault()) {
                defaultContext = context;
            } else if (context.hasProject(projectKey)) {
                return context;
            }
        }
        for (ProjectContext context : contexts) {
            if (!context.isDefault() && context.hasCategory(categoryId)) {
                return context;
            }
        }
        return defaultContext != null ? defaultContext : emptyDefault();
    }

    /** Контекст по id; {@code null} — такого контекста нет. */
    public static ProjectContext byId(List<ProjectContext> contexts, String id) {
        for (ProjectContext context : contexts) {
            if (context.id().equals(id)) {
                return context;
            }
        }
        return null;
    }

    /** Пустой встроенный контекст: ничего не включено, то есть плагин молчит. */
    public static ProjectContext emptyDefault() {
        return new ProjectContext(DEFAULT_ID, "Остальные проекты",
                Set.of(), Set.of(), Map.of(), Map.of());
    }

    private static List<?> readArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = MAPPER.readValue(raw, Object.class);
            return parsed instanceof List ? (List<?>) parsed : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static ProjectContext toContext(Map<?, ?> raw) {
        String id = str(raw.get("id"));
        if (id.isEmpty()) {
            return null;
        }
        return new ProjectContext(id, str(raw.get("name")),
                stringSet(raw.get("projects")), stringSet(raw.get("categories")),
                actions(raw.get("actions")), closedStatuses(raw.get("closedStatuses")));
    }

    private static Map<String, ProjectContext.ActionSetting> actions(Object raw) {
        Map<String, ProjectContext.ActionSetting> result = new LinkedHashMap<>();
        if (!(raw instanceof Map)) {
            return result;
        }
        ((Map<?, ?>) raw).forEach((key, value) -> {
            String actionKey = str(key);
            // действие, которого в плагине нет, пропускаем: иначе опечатка
            // в ключе жила бы в настройках и путала при отладке
            if (NotificationAction.byKey(actionKey) == null || !(value instanceof Map<?, ?> setting)) {
                return;
            }
            result.put(actionKey, new ProjectContext.ActionSetting(
                    Boolean.TRUE.equals(setting.get("enabled")),
                    str(setting.get("recipients"))));
        });
        return result;
    }

    private static Map<String, Set<String>> closedStatuses(Object raw) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (!(raw instanceof Map)) {
            return result;
        }
        ((Map<?, ?>) raw).forEach((key, value) -> {
            String projectKey = str(key);
            Set<String> statuses = stringSet(value);
            if (!projectKey.isEmpty() && !statuses.isEmpty()) {
                result.put(projectKey, statuses);
            }
        });
        return result;
    }

    private static Set<String> stringSet(Object raw) {
        Set<String> result = new LinkedHashSet<>();
        if (!(raw instanceof List)) {
            return result;
        }
        for (Object element : (List<?>) raw) {
            String value = str(element);
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static Map<String, Object> toMap(ProjectContext context) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", context.id());
        raw.put("name", context.name());
        raw.put("projects", new ArrayList<>(context.projects()));
        raw.put("categories", new ArrayList<>(context.categories()));

        Map<String, Object> actions = new LinkedHashMap<>();
        for (NotificationAction action : NotificationAction.values()) {
            ProjectContext.ActionSetting setting = context.action(action);
            if (setting == null) {
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("enabled", setting.isEnabled());
            value.put("recipients", setting.recipients());
            actions.put(action.key(), value);
        }
        raw.put("actions", actions);

        Map<String, Object> statuses = new LinkedHashMap<>();
        context.closedStatuses().forEach((project, ids) -> statuses.put(project, new ArrayList<>(ids)));
        raw.put("closedStatuses", statuses);
        return raw;
    }
}
