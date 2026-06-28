/* global React, Icon, AButton, SearchBar */

const EVENTS = [
  { name: "QCon London 2026", role: "Speaker", track: "AI Engineering", date: "May 14, 2026", location: "London", status: "upcoming" },
  { name: "Spring I/O Barcelona", role: "Panel", track: "Architecture", date: "Jun 02, 2026", location: "Barcelona", status: "upcoming" },
  { name: "London AI Engineering Meetup", role: "Host", track: "Community", date: "Apr 28, 2026", location: "London", status: "upcoming" },
  { name: "GOTO Copenhagen", role: "Speaker", track: "Microservices", date: "Oct 14, 2025", location: "Copenhagen", status: "past" },
  { name: "Devoxx UK", role: "Speaker", track: "Java", date: "May 09, 2025", location: "London", status: "past" },
];

function EventList() {
  return (
    <div className="a-list">
      <div className="a-toolbar">
        <div className="a-tabs">
          <button className="a-tab active">Upcoming <span className="a-tab__count">3</span></button>
          <button className="a-tab">Past <span className="a-tab__count">2</span></button>
        </div>
        <SearchBar placeholder="Search events…" />
      </div>

      <div className="a-events">
        {EVENTS.map((e, i) => (
          <article key={i} className={`a-event ${e.status === "past" ? "past" : ""}`}>
            <div className="a-event__date">
              <div className="a-event__day">{e.date.split(" ")[1].replace(",", "")}</div>
              <div className="a-event__month">{e.date.split(" ")[0].toUpperCase()}</div>
            </div>
            <div className="a-event__body">
              <div className="a-event__row">
                <h3>{e.name}</h3>
                <span className={`a-event__badge a-event__badge--${e.status}`}>
                  {e.status === "upcoming" ? "Upcoming" : "Past"}
                </span>
              </div>
              <div className="a-event__meta">
                <span><Icon name="user" size={12} /> {e.role}</span>
                <span><Icon name="hash" size={12} /> {e.track}</span>
                <span><Icon name="map-pin" size={12} /> {e.location}</span>
              </div>
            </div>
            <div className="a-event__actions">
              <button className="a-iconbtn" aria-label="Edit"><Icon name="edit-3" size={14} /></button>
              <button className="a-iconbtn" aria-label="More"><Icon name="more-horizontal" size={14} /></button>
            </div>
          </article>
        ))}
      </div>
    </div>
  );
}

window.EventList = EventList;
