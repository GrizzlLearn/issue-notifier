import React, { useEffect, useRef } from 'react';

const HTML_ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

const escapeHtml = s => String(s ?? '').replace(/[&<>"']/g, ch => HTML_ESCAPES[ch]);

/**
 * Обёртка над нативным виджетом Jira AJS.MultiSelect (AMD-модуль jira/ajs/select/multi-select) —
 * тем же компонентом, что рендерит поля Fix Versions/Components/User Picker в самой Jira.
 * Собственного UI/CSS не пишем — виджет тянет свою разметку и стили, "<select>" остаётся
 * источником истины во время редактирования, мы только слушаем его native "change".
 */
export default function AjsMultiSelect({ id, initialItems, url, onChange, ariaLabel }) {
  const selectRef = useRef(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  useEffect(() => {
    let cancelled = false;

    window.require(
      ['jira/ajs/select/multi-select', 'jira/ajs/list/group-descriptor', 'jira/ajs/list/item-descriptor', 'jquery'],
      (MultiSelect, GroupDescriptor, ItemDescriptor, jQuery) => {
        if (cancelled || !selectRef.current) return;

        function handleChange() {
          const keys = Array.from(selectRef.current.options)
            .filter(o => o.selected)
            .map(o => o.value);
          onChangeRef.current(keys);
        }

        jQuery(selectRef.current).on('change', handleChange);

        const widget = new MultiSelect({
          element: jQuery(selectRef.current),
          itemAttrDisplayed: 'label',
          showDropdownButton: false,
          removeOnUnSelect: true,
          ajaxOptions: {
            url,
            minQueryLength: 1,
            // бэкенд плагина уже отдаёт готовый [{value,label}] — свой REST,
            // не публичный REST API самой Jira (см. ProjectPickerResource/UserPickerResource)
            data: query => ({ query }),
            formatResponse(items) {
              const group = new GroupDescriptor({ weight: 0 });
              (items || []).forEach(item => {
                // html рендерится виджетом как разметка: имя проекта или пользователя
                // с '<' иначе выполнилось бы в выпадающем списке
                group.addItem(new ItemDescriptor({
                  value: item.value,
                  label: item.label,
                  html: escapeHtml(item.label),
                }));
              });
              return [group];
            },
          },
        });

        // <select> скрыт, поэтому htmlFor лейбла не доходит до реального поля ввода:
        // имя проставляем на input, который создал виджет
        if (ariaLabel) {
          const field = widget.$field
            || jQuery(selectRef.current).closest('.jira-multi-select').find('input').first();
          field?.attr?.('aria-label', ariaLabel);
        }
      }
    );

    return () => { cancelled = true; };
    // Виджет создаётся один раз на маунт — initialItems/url используются только
    // при инициализации (см. использование AjsMultiSelect в UserSettingsModal).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <select
      ref={selectRef}
      id={id}
      multiple
      defaultValue={initialItems.map(item => item.value)}
      style={{ display: 'none' }}
    >
      {initialItems.map(item => (
        <option key={item.value} value={item.value}>{item.label}</option>
      ))}
    </select>
  );
}
