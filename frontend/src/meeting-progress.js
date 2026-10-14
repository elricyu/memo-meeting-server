export function meetingProgress(meeting, transcriptCount = meeting.transcriptCount) {
  if (meeting.status !== 'COMPLETED') return []
  const labels = []
  if (transcriptCount === 0) labels.push({ text: '未转录', tone: 'pending' })
  if (meeting.summaryStatus === 'RUNNING') labels.push({ text: meeting.summary?.trim() ? '纪要更新中' : '纪要生成中', tone: 'working' })
  else if (meeting.summaryStatus === 'FAILED') labels.push({ text: meeting.summary?.trim() ? '纪要更新失败' : '纪要生成失败', tone: 'failed' })
  else if (!meeting.summary?.trim()) labels.push({ text: '未生成纪要', tone: 'pending' })
  return labels
}
