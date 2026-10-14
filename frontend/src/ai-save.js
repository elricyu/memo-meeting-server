/**
 * Confirm that a successful write is visible in a fresh server read.
 * @param {{mode:string,ready:boolean,updatedAt:string|null,provider:string,model:string}} saved
 * @param {Record<string,{mode:string,ready:boolean,updatedAt:string|null,provider:string,model:string}>} persisted
 * @param {string} kind
 */
export function confirmAiSave(saved, persisted, kind) {
  const current = persisted?.[kind]
  if (saved?.mode !== 'GLOBAL' || saved.ready !== true || !saved.updatedAt ||
      current?.mode !== 'GLOBAL' || current.ready !== true ||
      current.updatedAt !== saved.updatedAt || ['provider', 'model', 'appId', 'diarization', 'language', 'workspaceId', 'vocabularyId', 'reuseAsrKey'].some(field => current[field] !== saved[field])) {
    throw new Error('服务未确认配置已保存并生效，请重新打开设置检查；当前不能显示保存成功。')
  }
  return persisted
}
