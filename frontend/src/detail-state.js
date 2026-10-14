export function detailNeedsLive(detail) {
  return !!detail && (['WAITING','CONNECTING','RECORDING','FINALIZING','REPROCESSING'].includes(detail.meeting.status) || detail.meeting.summaryStatus === 'RUNNING')
}
export function defaultDetailTab(detail) {
  return detail?.meeting.status === 'COMPLETED' && (detail.meeting.summaryStatus === 'RUNNING' || (detail.meeting.summaryStatus === 'DONE' && detail.meeting.summary)) ? 'summary' : 'transcript'
}
