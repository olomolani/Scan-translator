Screen Translate
1) Install Android Studio, then File > Open > select this folder.
2) Wait for Gradle sync (if asked for a Gradle version choose 8.7).
3) Connect your phone (USB debugging) and press Run, or Build > Build APK(s).
4) In the app: choose languages, press Start, allow "Display over other apps" and screen capture.
5) Translate once with internet so each language pack is downloaded; after that it is fully offline.

Build options without Android Studio:
- On Linux/CT: run ./build.sh  (APK ends up in app/build/outputs/apk/debug/)
- On GitHub: push this folder to a repo; Actions > Build APK > download artifact app-debug
