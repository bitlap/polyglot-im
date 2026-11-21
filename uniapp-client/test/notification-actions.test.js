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


test('单聊撤回墓碑和清空水位阻止迟到历史恢复正文', () => {
  const data=JSON.parse(readFileSync(new URL('../../protocol/fixtures/direct-controls.json', import.meta.url)));
  let state=applyPage(emptyCache(),data.initial);
  state=applyPage(state,data.recalled);
  state=applyPage(state,data.stale_history);
  const message=state.conversations['control-chat'].messages['control-message'];
  assert.equal(message.type,'chat.recalled');
  assert.equal(message.payload.text,undefined);
  state=applyPage(state,data.cleared);
  state=applyPage(state,data.stale_history);
  assert.deepEqual(state.conversations['control-chat'].messages,{});
  assert.equal(state.conversations['control-chat'].clear_seq,'1');
});


test('账号删除响应不包含身份或凭据', () => {
  const data=JSON.parse(readFileSync(new URL('../../protocol/fixtures/account-deletion.json', import.meta.url)));
  assert.deepEqual(data,{code:200,message:'success',data:{deleted:true}});
});

 test('关闭回执可见性后迟到历史不恢复已读', () => {
  const data=JSON.parse(readFileSync(new URL('../../protocol/fixtures/direct-controls.json', import.meta.url)));
  let state=applyPage(emptyCache(),data.receipt_visible);
  assert.equal(state.conversations['control-chat'].peer_read_seq,'1');
  state=applyPage(state,data.receipt_hidden);
  state=applyPage(state,data.receipt_visible);
  assert.equal(state.conversations['control-chat'].peer_read_seq,'0');
  assert.equal(state.conversations['control-chat'].peer_read_visible,false);
});
