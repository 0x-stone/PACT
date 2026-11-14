/**
 * PACT Dashboard — Runtime Environment Configuration
 *
 * This file is loaded by index.html before app.js.
 *
 * You can set PACT_BACKEND_URL below, or start the server via:
 *   export PACT_BACKEND_URL=https://quickness-shining-latticed.ngrok-free.dev
 *   python3 dashboard/serve.py 8080
 */
window.__ENV__ = Object.assign(
  {
    PACT_BACKEND_URL: "https://quickness-shining-latticed.ngrok-free.dev",
  },
  window.__ENV__ || {}
);
