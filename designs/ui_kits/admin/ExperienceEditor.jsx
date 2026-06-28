/* global React, Icon, AButton, Status */

const ROLES = [
  { role: "Head of Engineering", org: "Global", dates: "Aug 2021 — Present", current: true },
  { role: "Lead Engineer", org: "Y-Tree", dates: "Jan 2019 — Jul 2021" },
  { role: "Senior Engineer", org: "Upp", dates: "Mar 2016 — Dec 2018" },
  { role: "Engineer · Pivotal Labs", org: "Pivotal", dates: "Sep 2012 — Feb 2016" },
];

function ExperienceEditor() {
  return (
    <div className="a-exp">
      <div className="a-card">
        <header className="a-card__head">
          <h3>Roles <span className="a-card__sub">drag to reorder</span></h3>
          <AButton variant="primary" icon="plus">Add role</AButton>
        </header>
        <ul className="a-exp__list">
          {ROLES.map((r, i) => (
            <li key={i}>
              <div className="a-exp__handle"><Icon name="grip-vertical" size={14} /></div>
              <div className="a-exp__logo">{r.org[0]}</div>
              <div className="a-exp__body">
                <div className="a-exp__title">{r.role}</div>
                <div className="a-exp__sub">{r.org} · {r.dates}</div>
              </div>
              {r.current && <span className="a-status a-status--info"><span className="dot" />Current</span>}
              <button className="a-iconbtn"><Icon name="edit-3" size={14} /></button>
              <button className="a-iconbtn"><Icon name="trash-2" size={14} /></button>
            </li>
          ))}
        </ul>
      </div>

      <div className="a-card">
        <header className="a-card__head"><h3>Edit role</h3></header>
        <div className="a-form">
          <div className="a-form__row">
            <label>Role title<input defaultValue="Head of Engineering" /></label>
            <label>Organisation<input defaultValue="Global · Commercial Trading" /></label>
          </div>
          <div className="a-form__row">
            <label>Start date<input defaultValue="Aug 2021" /></label>
            <label>End date<input defaultValue="Present" /></label>
            <label>Location<input defaultValue="Holborn, London" /></label>
          </div>
          <label>Description
            <textarea rows="3" defaultValue="Leading 30+ engineers across three product pillars. Driving AI-native delivery and event-driven architecture on Kafka + Spring Boot." />
          </label>
          <label>Tags
            <div className="a-form__tags">
              <span className="a-tag">Leadership <Icon name="x" size={10} /></span>
              <span className="a-tag">AI-Native <Icon name="x" size={10} /></span>
              <span className="a-tag">Kafka <Icon name="x" size={10} /></span>
              <button className="a-tag a-tag--add"><Icon name="plus" size={11} /> Add</button>
            </div>
          </label>
          <div className="a-form__actions">
            <AButton variant="ghost">Cancel</AButton>
            <AButton variant="primary" icon="save">Save changes</AButton>
          </div>
        </div>
      </div>
    </div>
  );
}

window.ExperienceEditor = ExperienceEditor;
