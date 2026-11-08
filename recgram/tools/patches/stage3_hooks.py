#!/usr/bin/env python3
"""Этап 3: точки вызова записи звонков в коде Telegram/WebRTC."""
import pathlib, sys
root = pathlib.Path(sys.argv[1]) / "TMessagesProj/src/main/java"

def patch(rel, old, new):
    p = root / rel
    s = p.read_text(encoding="utf-8")
    if s.count(old) != 1:
        sys.exit(f"[{rel}] якорь найден {s.count(old)} раз")
    p.write_text(s.replace(old, new), encoding="utf-8")
    print("ok", rel)

a = "          // It's possible we've been shut down during the read, and stopRecording() tried and\n"
patch("org/webrtc/voiceengine/WebRtcAudioRecord.java", a,
      "          if (captureType == 0) org.recgram.rec.CallRecorder.onMic(byteBuffer, bytesRead, requestedSampleRate, requestedChannels); // RecGram\n" + a)
a = "          nativeGetPlayoutData(sizeInBytes, nativeAudioTrack);\n        } catch (Throwable e) {\n          keepAlive = false;\n          continue;\n        }\n"
patch("org/webrtc/voiceengine/WebRtcAudioTrack.java", a,
      a + "        org.recgram.rec.CallRecorder.onPlayout(byteBuffer, sizeInBytes, sampleRate, bytesPerFrame / 2); // RecGram\n")
a = "\t\tcurrentState = state;\n\t\tif (currentState == STATE_ESTABLISHED) {\n\t\t\tdestroyConverting();\n"
patch("org/telegram/messenger/voip/VoIPService.java", a,
      "\t\tcurrentState = state;\n\t\torg.recgram.rec.CallRecordController.onCallState(this, state); // RecGram\n\t\tif (currentState == STATE_ESTABLISHED) {\n\t\t\tdestroyConverting();\n")
a = "        statusLayout.addView(statusTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 6));\n"
patch("org/telegram/ui/VoIPFragment.java", a,
      a + "        statusLayout.addView(new org.recgram.rec.RecChip(context), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 4)); // RecGram\n")
