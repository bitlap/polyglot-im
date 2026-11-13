import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {applyPage,emptyCache} from '../src/domain/cache.js';

test('单条已读、删除墓碑与迟到历史共享契约', () => {
  const data=JSON.parse(readFileSync(new URL('../../protocol/fixtures/notification-actions.json', import.meta.url)));
  let state=applyPage(emptyCache(), data.initial);
  state=applyPage(state, data.read);
  assert.equal(state.read_seq,'0');
  assert.equal(state.unread_count,'2');
  assert.equal(state.messages['notice-action'].is_read,true);
  state=applyPage(state,data.delete);
  const stale={messages:data.initial.messages};
  assert.deepEqual(applyPage(state,{...stale,history_position:'3'}),state);
  state=applyPage(state,stale);
  assert.equal(state.messages['notice-action'].is_deleted,true);
  assert.equal(state.unread_count,'1');
  assert.deepEqual(applyPage(state,{messages:[]},true).messages,{});
});
