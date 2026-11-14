/**
 * PACT Dashboard — Runtime Environment Configuration
 *
 * This file is loaded by index.html before app.js.
 *
 * You can set PACT_BACKEND_URL below, or start the server via:
 *   export PACT_BACKEND_URL=http://97.107.130.182:8000
 *   python3 dashboard/serve.py 8080
 */
window.__ENV__ = Object.assign(
  {
    PACT_BACKEND_URL: "http://97.107.130.182:8000",
  },
  window.__ENV__ || {}
);
