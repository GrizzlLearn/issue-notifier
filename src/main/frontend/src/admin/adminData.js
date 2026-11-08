// Справочники страницы (проекты, статусы, каталог действий) сервлет кладёт прямо
// в HTML — см. AdminPageData. Поэтому поиск проектов идёт по массиву в памяти
// браузера, без запросов на каждое нажатие клавиши.
export const PAGE_DATA = window.ISSUE_NOTIFIER_DATA || { projects: [], statuses: [], actions: [], userFields: [] };

export const DEFAULT_CONTEXT = 'default';
export const hintStyle = { fontSize: 11, color: '#707070' };

// Проекты контекста: отмеченные явно плюс проекты отмеченных категорий.
// Разворот только для экрана — в настройке категории остаются категориями,
// иначе новый проект в категории пришлось бы отмечать руками.
export function contextProjects(context) {
  const categories = context.categories || [];
  const byCategory = PAGE_DATA.projects
    .filter(p => p.category && categories.includes(p.category))
    .map(p => p.value);
  return [...new Set([...(context.projects || []), ...byCategory])];
}

// Справочники уже в PAGE_DATA, загружать на вкладках нечего.
export const PROJECT_LABELS = Object.fromEntries(PAGE_DATA.projects.map(p => [p.value, p.label]));
export const CATEGORY_LABELS = Object.fromEntries((PAGE_DATA.categories || []).map(c => [c.value, c.label]));

