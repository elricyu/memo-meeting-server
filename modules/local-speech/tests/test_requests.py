import asyncio, io, tempfile, unittest, uuid
from pathlib import Path
from unittest.mock import patch
import numpy as np
import soundfile as sf
from fastapi.testclient import TestClient
import service
class Requests(unittest.TestCase):
 def test_retry_reuses_task_and_token_protects_all_endpoints(self):
  with tempfile.TemporaryDirectory() as tmp, patch.object(service,'DATA',Path(tmp)), patch.object(service,'DB',Path(tmp)/'jobs.sqlite3'), patch.object(service,'engine',object()), patch.object(service,'queue',asyncio.Queue(8)):
   service.initialize();client=TestClient(service.app);audio=io.BytesIO();sf.write(audio,np.zeros(1600),16000,format='WAV',subtype='PCM_16');data=audio.getvalue();key=str(uuid.uuid4())
   for _ in range(2):
    r=client.post('/v1/jobs',data={'request_id':key},files={'file':('meeting.wav',data,'audio/wav')});self.assertEqual(r.status_code,202);self.assertEqual(r.json()['jobId'],key)
   self.assertEqual(service.queue.qsize(),1)
   with patch.dict('os.environ',{'LOCAL_SPEECH_TOKEN':'test-token'}):
    self.assertEqual(client.get('/health').status_code,401)
    self.assertEqual(client.get('/health',headers={'Authorization':'Bearer test-token'}).status_code,200)
