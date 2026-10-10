import { useId, useState } from 'react';
import { Controller, type Control, type UseFormRegister } from 'react-hook-form';

import { toggleChoice, type AssistantEditorOption, type FieldControl } from './fieldControls';

type Values = Record<string, unknown>;

function Chip({ type, name, label, ariaLabel, checked, onChange, short }: {
  type: 'radio' | 'checkbox';
  name: string;
  label: string;
  ariaLabel?: string;
  checked: boolean;
  onChange: () => void;
  short?: boolean;
}) {
  return (
    <label className={`assistant-chip${checked ? ' assistant-chip--on' : ''}${short ? ' assistant-chip--short' : ''}`}>
      <input type={type} name={name} checked={checked} aria-label={ariaLabel} onChange={onChange} />
      <span>{label}</span>
    </label>
  );
}

/** Radio chips, plus "Other" with a text box for a type the list does not have. */
function EventTypeChoice({ name, choices, value, onChange }: {
  name: string;
  choices: AssistantEditorOption[];
  value: unknown;
  onChange: (value: string) => void;
}) {
  const current = typeof value === 'string' ? value : '';
  const known = choices.find((choice) => choice.value.toLowerCase() === current.trim().toLowerCase());
  const [other, setOther] = useState(current !== '' && !known);
  const otherOn = other || (current !== '' && !known);
  return (
    <>
      <div className="assistant-chips">
        {choices.map((choice) => (
          <Chip
            key={choice.value}
            type="radio"
            name={name}
            label={choice.label}
            checked={!otherOn && known?.value === choice.value}
            onChange={() => {
              setOther(false);
              onChange(choice.value);
            }}
          />
        ))}
        <Chip
          type="radio"
          name={name}
          label="Other"
          checked={otherOn}
          onChange={() => {
            setOther(true);
            if (known) onChange('');
          }}
        />
      </div>
      {otherOn && (
        <input
          className="assistant-field__other"
          aria-label="Other type"
          placeholder="For example: Therapy, Birthday"
          value={current}
          onChange={(event) => onChange(event.target.value)}
        />
      )}
    </>
  );
}

/**
 * One field of a Quick add card. Chips and other controls that are not a single input are a
 * labelled group rather than a label, since a label may hold only one control.
 */
export function AssistantField({ name, label, control, wide, error, form }: {
  name: string;
  label: string;
  control: FieldControl;
  wide: boolean;
  error?: string;
  form: { control: Control<Values>; register: UseFormRegister<Values> };
}) {
  const id = useId();
  const labelId = `${id}-label`;
  const errorId = `${id}-error`;
  const grouped = control.kind === 'radio' || control.kind === 'checkboxes' || control.kind === 'eventType';
  const className = [
    'assistant-field',
    wide ? 'assistant-field--wide' : '',
    control.kind === 'boolean' ? 'assistant-field--inline' : '',
  ].filter(Boolean).join(' ');
  const message = error && <small className="assistant-field__error" id={errorId}>{error}</small>;
  const described = error ? errorId : undefined;
  // Named by the heading alone: an error inside the label would otherwise join the name.
  const aria = { 'aria-labelledby': labelId, 'aria-describedby': described };

  if (grouped) {
    return (
      <div
        className={className}
        role={control.kind === 'checkboxes' ? 'group' : 'radiogroup'}
        aria-labelledby={labelId}
        aria-describedby={described}
      >
        <span id={labelId}>{label}</span>
        <Controller
          name={name}
          control={form.control}
          render={({ field }) => {
            if (control.kind === 'eventType') {
              return <EventTypeChoice name={id} choices={control.choices} value={field.value} onChange={field.onChange} />;
            }
            if (control.kind === 'checkboxes') {
              const selected = Array.isArray(field.value) ? field.value : [];
              return (
                <div className="assistant-chips">
                  {control.choices.map((choice) => (
                    <Chip
                      key={choice.value}
                      type="checkbox"
                      name={id}
                      label={choice.label}
                      // Weekday chips show "Mon" but read out "Monday".
                      ariaLabel={control.short ? choice.value.charAt(0).toUpperCase() + choice.value.slice(1) : undefined}
                      short={control.short}
                      checked={selected.includes(choice.value)}
                      onChange={() => field.onChange(toggleChoice(field.value, choice.value, control.choices))}
                    />
                  ))}
                </div>
              );
            }
            const current = field.value == null ? '' : String(field.value);
            return (
              <div className="assistant-chips">
                {control.kind === 'radio' && control.choices.map((choice) => (
                  <Chip
                    key={choice.value}
                    type="radio"
                    name={id}
                    label={choice.label}
                    checked={current === choice.value}
                    onChange={() => field.onChange(choice.value)}
                  />
                ))}
              </div>
            );
          }}
        />
        {message}
      </div>
    );
  }

  return (
    <label className={className}>
      <span id={labelId}>{label}</span>
      {control.kind === 'select' ? (
        <select {...aria} {...form.register(name)}>
          {control.placeholder && <option value="">Select…</option>}
          {control.choices.map((choice) => (
            <option key={choice.value} value={choice.value}>{choice.label}</option>
          ))}
        </select>
      ) : control.kind === 'boolean' ? (
        <Controller
          name={name}
          control={form.control}
          // Unticked is false, but a value nobody touched stays as it came, even null.
          render={({ field }) => (
            <input
              type="checkbox"
              {...aria}
              checked={field.value === true}
              onChange={(event) => field.onChange(event.target.checked)}
            />
          )}
        />
      ) : control.kind === 'percent' ? (
        <Controller
          name={name}
          control={form.control}
          render={({ field }) => (
            <span className="assistant-input-suffix">
              <input
                type="number"
                inputMode="numeric"
                min={0}
                max={100}
                step={1}
                {...aria}
                value={field.value == null ? '' : String(field.value)}
                // The server keeps a whole number; an empty box goes back as null, which is 50.
                onChange={(event) => field.onChange(event.target.value === '' ? null : Number(event.target.value))}
              />
              <span aria-hidden="true">%</span>
            </span>
          )}
        />
      ) : control.kind === 'long' ? (
        <textarea rows={3} {...aria} {...form.register(name)} />
      ) : (
        <input
          type={control.kind === 'date' || control.kind === 'time' ? control.kind : 'text'}
          {...aria}
          {...form.register(name)}
        />
      )}
      {message}
    </label>
  );
}
