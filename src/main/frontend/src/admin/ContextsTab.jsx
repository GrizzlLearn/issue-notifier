import React, { useState, useEffect, useRef } from 'react';
import { PAGE_DATA, DEFAULT_CONTEXT, PROJECT_LABELS, CATEGORY_LABELS, hintStyle } from './adminData';

const MAX_SUGGESTIONS = 20;

// Пикер значений из справочника: их могут быть сотни, поэтому список целиком
// не рисуем — фильтруем по вводу и показываем выбранное чипами. Так выбираются
// и проекты, и категории: в обоих случаях перечислять всё галками нельзя.
// Список подсказок управляется с клавиатуры: ↑/↓ — перебор, Enter — выбрать, Esc — закрыть.
// id обязателен и должен быть уникальным на странице: пикеров на ней столько же,
// сколько контекстов, а aria-controls ссылается на конкретный список.
function ItemPicker({ id, items, selected, labels, onAdd, onRemove, placeholder, chosenText }) {
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const [closed, setClosed] = useState(false);
  const listRef = useRef(null);

  // активный пункт держим в зоне видимости только при переборе стрелками —
  // в ref-колбэке это срабатывало на каждый рендер и дёргало список при вводе
  useEffect(() => {
    listRef.current?.children[active]?.scrollIntoView({ block: 'nearest' });
  }, [active]);

  const text = query.trim().toLowerCase();
  const suggestions = text && !closed
    ? items
        .filter(p => !selected.includes(p.value)
          && (p.value.toLowerCase().includes(text) || p.label.toLowerCase().includes(text)))
        .slice(0, MAX_SUGGESTIONS)
    : [];

  function changeQuery(value) {
    setQuery(value);
    setActive(0);
    setClosed(false);
  }

  function pick(item) {
    onAdd(item.value);
    setQuery('');
    setActive(0);
  }

  function handleKeyDown(e) {
    if (e.key === 'Escape') {
      setClosed(true);
      return;
    }
    if (suggestions.length === 0) {
      return;
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive(prev => Math.min(prev + 1, suggestions.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive(prev => Math.max(prev - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      pick(suggestions[active]);
    }
  }

  return (
    <div style={{ position: 'relative' }}>
      {selected.length > 0 && (
        <div style={{ marginBottom: 6 }}>
          {selected.map(key => (
            <span key={key} className="aui-label" style={{ marginRight: 6, display: 'inline-block' }}>
              {labels[key] || key}
              <button
                type="button"
                className="in-chip-remove"
                onClick={() => onRemove(key)}
                aria-label={'Убрать ' + (labels[key] || key)}
              >×</button>
            </span>
          ))}
        </div>
      )}

      <input
        id={id}
        className="text"
        type="text"
        value={query}
        onChange={e => changeQuery(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        style={{ width: '100%' }}
        role="combobox"
        aria-expanded={suggestions.length > 0}
        aria-autocomplete="list"
        // список есть в DOM только когда есть подсказки, иначе ссылка висела бы в пустоту
        aria-controls={suggestions.length > 0 ? `${id}-suggestions` : undefined}
        aria-activedescendant={suggestions.length > 0 ? `${id}-option-${active}` : undefined}
      />

      {suggestions.length > 0 && (
        <ul className="in-suggestions" id={`${id}-suggestions`} role="listbox" ref={listRef}>
          {suggestions.map((item, index) => (
            <li
              key={item.value}
              id={`${id}-option-${index}`}
              role="option"
              aria-selected={index === active}
              className={'in-suggestion' + (index === active ? ' is-active' : '')}
              onMouseEnter={() => setActive(index)}
              onClick={() => pick(item)}
            >
              {item.label}
              {item.serviceDesk && <span style={{ ...hintStyle, marginLeft: 6 }}>Service Desk</span>}
            </li>
          ))}
        </ul>
      )}

      {text && suggestions.length === 0 && !closed && (
        <div style={{ ...hintStyle, marginTop: 4 }}>
          {items.some(p => selected.includes(p.value)
            && (p.value.toLowerCase().includes(text) || p.label.toLowerCase().includes(text)))
            ? chosenText
            : 'Ничего не найдено.'}
        </div>
      )}
    </div>
  );
}

// Карточка одного контекста: имя, состав (категории, SD-проекты, остальные через
// пикер) и удаление. У встроенного контекста «Остальные проекты» состава нет —
// в него попадает всё, что не попало в явные контексты.
function ContextCard({ context, projects, labels, onChange, onRemove }) {
  const categories = PAGE_DATA.categories || [];
  const chosenCategories = context.categories || [];
  const selected = context.projects || [];

  // проект, попавший в контекст через категорию, отмечен, но снимается только категорией —
  // иначе галка возвращалась бы сама и это выглядело бы сбоем
  const viaCategory = new Set(projects
    .filter(p => p.category && chosenCategories.includes(p.category))
    .map(p => p.value));

  const sdProjects = projects.filter(p => p.serviceDesk);
  const sdKeys = sdProjects.map(p => p.value);
  const otherSelected = selected.filter(key => !sdKeys.includes(key));
  const otherViaCategory = [...viaCategory].filter(key => !sdKeys.includes(key) && !selected.includes(key));

  const setSelected = next => onChange({ ...context, projects: next });

  const setCategories = next => onChange({ ...context, categories: next });

  if (context.id === DEFAULT_CONTEXT) {
    return (
      <fieldset className="in-section">
        <legend>Остальные проекты</legend>
        <div style={hintStyle}>
          Встроенный контекст: сюда попадают задачи проектов, которых нет ни в одном
          контексте выше. Удалить его нельзя. Какие действия в нём включены — смотри на вкладке «Действия».
        </div>
      </fieldset>
    );
  }

  return (
    <fieldset className="in-section">
      <legend>{context.name || 'Новый контекст'}</legend>

      <div className="field-group" style={{ marginBottom: 16 }}>
        <label className="label" htmlFor={`in-ctx-name-${context.id}`}>Название контекста</label>
        <input
          id={`in-ctx-name-${context.id}`}
          className="text"
          type="text"
          value={context.name || ''}
          onChange={e => onChange({ ...context, name: e.target.value })}
          placeholder="Например: заявки на доступ"
          style={{ width: '100%', maxWidth: 400 }}
        />
      </div>

      <div style={{ marginBottom: 16 }}>
        <label className="label" htmlFor={`in-ctx-categories-${context.id}`}>
          Категории проектов
        </label>
        {categories.length === 0 && <div style={hintStyle}>Категорий проектов в инстансе нет.</div>}
        {categories.length > 0 && (
          <ItemPicker
            id={`in-ctx-categories-${context.id}`}
            items={categories}
            selected={chosenCategories}
            labels={CATEGORY_LABELS}
            onAdd={value => setCategories([...chosenCategories, value])}
            onRemove={value => setCategories(chosenCategories.filter(c => c !== value))}
            placeholder="Начните вводить название категории"
            chosenText="Категория уже выбрана."
          />
        )}
        <div style={hintStyle}>
          Проект, добавленный в выбранную категорию позже, попадёт в контекст сам —
          но закрывающие статусы для него всё равно нужно выбрать на вкладке «Действия».
        </div>
      </div>

      <div style={{ marginBottom: 16 }}>
        <div style={{ fontWeight: 600, marginBottom: 6 }}>Service Desk</div>
        {sdProjects.length === 0 && <div style={hintStyle}>Service Desk-проекты не найдены.</div>}
        <div className="in-check-grid">
          {sdProjects.map(p => (
            <label key={p.value} className="in-check">
              <input
                type="checkbox"
                checked={selected.includes(p.value) || viaCategory.has(p.value)}
                disabled={viaCategory.has(p.value)}
                onChange={e => setSelected(e.target.checked
                  ? [...selected, p.value]
                  : selected.filter(k => k !== p.value))}
              />
              <span>
                {p.label}
                {viaCategory.has(p.value) && <span className="in-check-note"> через категорию</span>}
              </span>
            </label>
          ))}
        </div>
      </div>

      <div>
        <div style={{ fontWeight: 600, marginBottom: 6 }}>Остальные проекты</div>
        <ItemPicker
          id={`in-ctx-projects-${context.id}`}
          items={projects}
          selected={otherSelected}
          labels={labels}
          onAdd={key => setSelected([...selected, key])}
          onRemove={key => setSelected(selected.filter(k => k !== key))}
          placeholder="Начните вводить ключ или название проекта"
          chosenText="Проект уже выбран."
        />
        {otherViaCategory.length > 0 && (
          <div style={{ ...hintStyle, marginTop: 6 }}>
            Через категории также включены: {otherViaCategory.map(key => labels[key] || key).join(', ')}
          </div>
        )}
      </div>

      <div style={{ marginTop: 16 }}>
        <button type="button" className="aui-button aui-button-link" style={{ padding: 0 }}
                onClick={onRemove}>
          Удалить контекст
        </button>
        <div style={hintStyle}>
          Его проекты вернутся в «Остальные проекты», а настройки действий контекста пропадут.
        </div>
      </div>
    </fieldset>
  );
}

// Вкладка «Контекст проектов»: сколько контекстов и что в каждом.
export default function ContextsTab({ contexts, setContexts }) {
  function update(id, next) {
    setContexts(contexts.map(c => (c.id === id ? next : c)));
  }

  function add() {
    // id выдаёт сервер при сохранении; пока контекст новый, ключ нужен только React
    setContexts([...contexts.filter(c => c.id !== DEFAULT_CONTEXT),
      { id: `new-${Date.now()}`, name: '', projects: [], categories: [], actions: {}, closedStatuses: {} },
      ...contexts.filter(c => c.id === DEFAULT_CONTEXT)]);
  }

  const explicit = contexts.filter(c => c.id !== DEFAULT_CONTEXT);

  return (
    <>
      <div style={{ ...hintStyle, marginBottom: 12 }}>
        Контекст — это набор проектов со своим набором включённых действий: например,
        в одной категории нужны только упоминания, в другой ещё и переходы в закрывающий
        статус. Проект и категория входят ровно в один контекст. Тексты уведомлений общие
        на инстанс и задаются на вкладке «Действия».
      </div>

      {explicit.length === 0 && (
        <div className="aui-message aui-message-info" style={{ marginBottom: 12 }}>
          Явных контекстов нет — все проекты настраиваются как «Остальные проекты».
        </div>
      )}

      {contexts.map(context => (
        <ContextCard
          key={context.id}
          context={context}
          projects={PAGE_DATA.projects}
          labels={PROJECT_LABELS}
          onChange={next => update(context.id, next)}
          onRemove={() => setContexts(contexts.filter(c => c.id !== context.id))}
        />
      ))}

      <button type="button" className="aui-button" onClick={add}>Добавить контекст</button>
    </>
  );
}

