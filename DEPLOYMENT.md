#Steps for deployment of new version

1. up the version code and version name in Manifest file and sync the version code in build.gradle.
2. tag the Git repo with the new version and push.
3. build a signed APK of the app.
4. upload the app to Google play developer console
5. wait for it to be pushed as update.
6. meanwhile move a copy of the apk to https://demo.irmacard.org/irma.apk (/www/irmademo/live/htdocs on lilo)
