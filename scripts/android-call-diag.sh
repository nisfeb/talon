#!/usr/bin/env bash
# One-shot Android calling diagnosis over adb. Run it right after a
# test call has rung out (or been answered) on the phone. It answers,
# without guessing: which Android this is, whether telecom knows our
# account, what telecom did with the last call, whether the system
# call log has our rows (VoIP rows are hidden unless asked for), and
# what the app logged. Paste the whole output.
set -u
out=${1:-android-call-diag.txt}
{
  echo "== device =="
  for p in ro.product.model ro.build.version.release ro.build.version.sdk ro.build.version.sdk_minor ro.build.version.incremental ro.build.id; do
    printf "%-32s %s\n" "$p" "$(adb shell getprop $p 2>/dev/null | tr -d '\r')"
  done
  echo; echo "== telecom: our phone account(s) =="
  adb shell dumpsys telecom 2>/dev/null | tr -d '\r' | grep -iE "talon|nisfeb|self.?managed|LOG_SELF_MANAGED|integrat" | head -40
  echo; echo "== telecom: recent calls it handled (last 40 lines of the CallsManager/analytics section) =="
  adb shell dumpsys telecom 2>/dev/null | tr -d '\r' | grep -nE "CallsManager|Analytics|CallLog|CallLogManager" | head -20
  echo; echo "== system call log: plain query (what a dialer sees without asking for VoIP rows) =="
  # The call log is per user; the shell defaults to user 0, which is not
  # the profile the call happened in on a multi-user (GrapheneOS) phone.
  U=$(adb shell am get-current-user | tr -d "\r")
  adb shell 'content query --user '"$U"' --uri content://call_log/calls --projection number:type:date:subscription_component_name --sort "date DESC"' 2>&1 | tr -d '\r' | head -8
  echo; echo "== system call log: with include_voip_calls=true (16.1+ hides these by default) =="
  adb shell 'content query --user '"$U"' --uri "content://call_log/calls?include_voip_calls=true" --projection number:type:date:subscription_component_name --sort "date DESC"' 2>&1 | tr -d '\r' | head -8
  echo; echo "== system call log: VoIP-only uri (17) =="
  adb shell 'content query --user '"$U"' --uri content://call_log/calls/voip --sort "date DESC"' 2>&1 | tr -d '\r' | head -6 | cut -c1-300
  echo; echo "== full-screen intent permission =="
  adb shell appops get io.nisfeb.talon USE_FULL_SCREEN_INTENT 2>&1 | tr -d '\r'
  echo; echo "== app + telecom logcat (recent buffer) =="
  adb logcat -d -s TalonTelecom:* ModernTelecom:* TelecomCalls:* TalonConnectionService:* CallForegroundService:* Notifications:* TalonMessagingReceiver:* Telecom:* 2>/dev/null | tr -d '\r' | tail -80
  echo; echo "== full dumpsys telecom (attached for the maintainer) =="
  adb shell dumpsys telecom 2>/dev/null | tr -d '\r'
} > "$out" 2>&1
echo "wrote $out ($(wc -l < "$out") lines). Paste the part above 'full dumpsys telecom'; attach the rest if asked."
