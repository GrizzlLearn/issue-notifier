// Справочники страницы (проекты, статусы, каталог действий) сервлет кладёт прямо
// в HTML — см. AdminPageData. Поэтому поиск проектов идёт по массиву в памяти
// браузера, без запросов на каждое нажатие клавиши.
export const PAGE_DATA = window.ISSUE_NOTIFIER_DATA || { projects: [], statuses: [], actions: [], userFields: [] };

export const DEFAULT_CONTEXT = 'default';
export const hintStyle = { fontSize: 11, color: '#707070' };

// Состав для экрана совпадает с ProjectContexts.resolve: явный проект
// побеждает категорию, остальные проекты принадлежат встроенному контексту.
export function contextProjects(context, contexts) {
  return PAGE_DATA.projects.filter(project => {
    const explicit = contexts.find(c => c.id !== DEFAULT_CONTEXT
      && (c.projects || []).includes(project.value));
    const category = contexts.find(c => c.id !== DEFAULT_CONTEXT
      && project.category && (c.categories || []).includes(project.category));
    return (explicit || category)?.id === context.id
      || (!explicit && !category && context.id === DEFAULT_CONTEXT);
  }).map(project => project.value);
}

// Справочники уже в PAGE_DATA, загружать на вкладках нечего.
export const PROJECT_LABELS = Object.fromEntries(PAGE_DATA.projects.map(p => [p.value, p.label]));
export const CATEGORY_LABELS = Object.fromEntries((PAGE_DATA.categories || []).map(c => [c.value, c.label]));

