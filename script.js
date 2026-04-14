const events = [
  { title: "Afterwork Paint Night", date: "Freitag, 19:00", details: "Live Acoustic Set + Keramik Session" },
  { title: "Matcha & Mindfulness", date: "Samstag, 11:00", details: "Tea Ritual und bewusstes Bemalen" },
  { title: "Family Sunday", date: "Sonntag, 14:00", details: "Kinderfreundliche Motive & Mini-Workshop" }
];

const eventTicker = document.getElementById("eventTicker");
const eventGrid = document.getElementById("eventGrid");
const bookingForm = document.getElementById("bookingForm");
const formNote = document.getElementById("formNote");
const menuToggle = document.getElementById("menuToggle");
const navLinks = document.getElementById("navLinks");
const adminTrigger = document.getElementById("adminTrigger");
const adminDialog = document.getElementById("adminDialog");
const adminLogin = document.getElementById("adminLogin");
const adminPassword = document.getElementById("adminPassword");
const adminMessage = document.getElementById("adminMessage");

document.getElementById("year").textContent = new Date().getFullYear();

let tickerIndex = 0;
function renderEvents() {
  eventGrid.innerHTML = events
    .map((event) => `
      <article class="event-item reveal">
        <h3>${event.title}</h3>
        <p><strong>${event.date}</strong></p>
        <p>${event.details}</p>
      </article>`)
    .join("");
}

function rotateTicker() {
  eventTicker.textContent = `Nächstes Highlight: ${events[tickerIndex].title} · ${events[tickerIndex].date}`;
  tickerIndex = (tickerIndex + 1) % events.length;
}

bookingForm.addEventListener("submit", (e) => {
  e.preventDefault();
  const data = new FormData(bookingForm);
  const name = data.get("name");
  const date = data.get("date");
  const time = data.get("time");
  formNote.textContent = `Danke ${name}! Anfrage für ${date} um ${time} wurde gespeichert.`;
  bookingForm.reset();
});

menuToggle.addEventListener("click", () => navLinks.classList.toggle("open"));

adminTrigger.addEventListener("click", () => {
  adminMessage.textContent = "";
  adminPassword.value = "";
  adminDialog.showModal();
});

adminLogin.addEventListener("click", (e) => {
  e.preventDefault();
  if (adminPassword.value === "1357") {
    adminMessage.textContent = "Admin freigeschaltet. Eventverwaltung kann erweitert werden.";
    adminMessage.style.color = "#3ecf8e";
  } else {
    adminMessage.textContent = "Falsches Passwort.";
    adminMessage.style.color = "#ff6b6b";
  }
});

const observer = new IntersectionObserver((entries) => {
  entries.forEach((entry) => {
    if (entry.isIntersecting) entry.target.classList.add("visible");
  });
}, { threshold: 0.1 });

document.querySelectorAll(".reveal").forEach((el) => observer.observe(el));
renderEvents();
rotateTicker();
setInterval(rotateTicker, 3000);
