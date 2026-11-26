import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { validEndpoint, normalizeDeviceState } from '../src/validation.js';
describe('address validation', () => {
  it('accepts local IPv4 address with port', () => assert.equal(validEndpoint('192.168.1.2:37145'), true));
  it('rejects a missing port', () => assert.equal(validEndpoint('192.168.1.2'), false));
  it('rejects invalid ports and octets', () => { assert.equal(validEndpoint('192.168.1.2:65536'), false); assert.equal(validEndpoint('300.168.1.2:8080'), false); });
  it('rejects shell metacharacters', () => assert.equal(validEndpoint('127.0.0.1:5555;echo hi'), false));
  it('rejects public addresses and hostnames', () => { assert.equal(validEndpoint('8.8.8.8:5555'), false); assert.equal(validEndpoint('example.com:5555'), false); });
  it('shows unauthorized device state', () => assert.equal(normalizeDeviceState('unauthorized'), 'Authorize on phone'));
});
