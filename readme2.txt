Команда для лог с тел
DEV_PHONE=988a17424f4d4e584f
TS="$(date -v-10M '+%m-%d %H:%M:%S.000')"
adb -s "$DEV_PHONE" logcat -v time -b all -T "$TS" \
  | egrep "WEARPLUGIN|WEAR|BGSOURCE|GLUCOSE|NSCLIENT|WORKER|Bluetooth|GmsWearable|WearableService|Doze|PowerManager" \
  > /tmp/phone_focus_$(date +%H%M).log
ls -lh /tmp/phone_focus_$(date +%H%M).log