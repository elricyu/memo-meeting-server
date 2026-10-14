import unittest
from whisper_engine import word_groups


class SpeakerGroups(unittest.TestCase):
    def test_split_on_word_speaker_change_and_preserve_unknown(self):
        segments, missing = word_groups({"segments": [{"start": 0, "end": 4, "text": "你好好的未知", "words": [
            {"word": "你好", "start": 0, "end": 1, "speaker": "A"},
            {"word": "好的", "start": 1, "end": 2, "speaker": "B"},
            {"word": "未知", "start": 2, "end": 3}]}]})
        self.assertEqual([s["spk"] for s in segments], ["A", "B", None])
        self.assertEqual("".join(s["text"] for s in segments), "你好好的未知")
        self.assertEqual(missing, 0)

    def test_missing_alignment_word_is_retained(self):
        segments, missing = word_groups({"segments": [{"start": 0, "end": 2, "text": "你好2026", "speaker": "A", "words": [
            {"word": "你好", "start": 0, "end": 1, "speaker": "A"}, {"word": "2026"}]}]})
        self.assertEqual("".join(s["text"] for s in segments), "你好2026")
        self.assertEqual(missing, 1)


if __name__ == "__main__": unittest.main()
