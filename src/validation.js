export const endpointPattern = /^(?:(?:\d{1,3}\.){3}\d{1,3}|\[[0-9a-fA-F:]+\]|[a-zA-Z0-9][a-zA-Z0-9.-]*):\d{1,5}$/;
export function validEndpoint(value) {
  if (typeof value !== 'string' || !endpointPattern.test(value)) return false;
  const pos = value.lastIndexOf(':');
  const port = Number(value.slice(pos + 1));
  if (!Number.isInteger(port) || port < 1 || port > 65535) return false;
  const host = value.slice(0, pos);
  if (host.includes('.') && /^(?:\d+\.){3}\d+$/.test(host)) {
    if (host.split('.').some(n => Number(n) > 255)) return false;
  }
  return true;
}
export function normalizeDeviceState(state) {
  return state === 'device' ? 'Ready' : state === 'unauthorized' ? 'Authorize on phone' : state === 'offline' ? 'Offline' : state;
}
