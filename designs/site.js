/* simonrowe.dev — shared chrome behaviour: theme toggle + mobile menu */
(function () {
  // Render any lucide icons already in the DOM
  if (window.lucide) window.lucide.createIcons();

  var root = document.documentElement;
  var saved = localStorage.getItem("sr-theme");
  if (saved) root.setAttribute("data-theme", saved);

  var toggle = document.getElementById("theme-toggle");
  if (toggle) {
    toggle.addEventListener("click", function () {
      var next = root.getAttribute("data-theme") === "light" ? "dark" : "light";
      root.setAttribute("data-theme", next);
      localStorage.setItem("sr-theme", next);
    });
  }

  var menuBtn = document.getElementById("menu-toggle");
  var menu = document.getElementById("mobile-menu");
  if (menuBtn && menu) {
    menuBtn.addEventListener("click", function () { menu.classList.toggle("is-open"); });
    menu.addEventListener("click", function (e) {
      if (e.target.tagName === "A") menu.classList.remove("is-open");
    });
  }
})();
