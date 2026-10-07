// PayFi frontend configuration.
// Copy to config.js (gitignored) and adjust for your deployment:
//   cp frontend/config.example.js frontend/config.js
window.PAYFI_CONFIG = {
  // Base URL of the Corda REST API. '/api/v5_2' assumes the nginx proxy from
  // docs/PayFi_Corda52_Complete_Reference_v4.md, which forwards /api/ to https://localhost:8888/api/.
  apiBase: '/api/v5_2',

  // Corda REST credentials. Leave empty when the proxy injects the
  // Authorization header itself (recommended: nothing secret in the browser).
  username: '',
  password: ''
};
