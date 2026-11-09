import http from 'k6/http';
import { check, fail } from 'k6';
import { businessErrors, config } from './config.js';

function buildHeaders(token, extraHeaders = {}) {
  const headers = {
    'Content-Type': 'application/json',
    ...extraHeaders,
  };

  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  return headers;
}

function parseBody(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

export function request(method, path, { token, body, params = {}, expectedStatus, tags = {} } = {}) {
  const url = `${config.baseUrl}${path}`;
  const response = http.request(method, url, body ? JSON.stringify(body) : null, {
    headers: buildHeaders(token),
    tags,
    ...params,
  });

  const success = check(response, {
    // Agrupa pelo fluxo quando houver: caminhos com o id da OS gerariam um check por OS.
    [`${method} ${tags.flow || path} -> ${expectedStatus}`]: (res) => res.status === expectedStatus,
  });

  if (!success) {
    businessErrors.add(1);
    fail(`Unexpected status for ${method} ${path}: ${response.status} body=${response.body}`);
  }

  businessErrors.add(0);
  return {
    response,
    payload: parseBody(response),
  };
}

export function getData(method, path, options) {
  const { payload } = request(method, path, options);
  return payload?.data;
}

// GET sem check: usado para aguardar passos assincronos da Saga (404/estado antigo sao esperados).
export function peek(path, token, tags = {}) {
  const response = http.get(`${config.baseUrl}${path}`, {
    headers: buildHeaders(token),
    tags: { ...tags, polling: 'true' },
    responseCallback: http.expectedStatuses({ min: 200, max: 499 }),
  });
  return { status: response.status, data: parseBody(response)?.data };
}
