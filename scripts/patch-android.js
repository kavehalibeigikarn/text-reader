// Runs in GitHub Actions after `npx cap add android`.
// Applies app-specific Android settings (dark bars, version number, fixed signing key).
const fs = require('fs');

function patch(file, from, to) {
  const s = fs.readFileSync(file, 'utf8');
  if (!s.includes(from)) throw new Error(`patch target not found in ${file}:\n${from}`);
  fs.writeFileSync(file, s.replace(from, to));
}

// 1) dark status/navigation bars matching the app background #14110D
fs.writeFileSync('android/app/src/main/res/values/app_colors.xml',
`<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="appBackground">#14110D</color>
</resources>
`);
patch('android/app/src/main/res/values/styles.xml',
`        <item name="android:background">@null</item>
    </style>`,
`        <item name="android:background">@null</item>
        <item name="android:windowBackground">@color/appBackground</item>
        <item name="android:statusBarColor">@color/appBackground</item>
        <item name="android:navigationBarColor">@color/appBackground</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:windowLightNavigationBar">false</item>
    </style>`);

// 2) version number grows with every build, so a new APK installs as an update
const g = 'android/app/build.gradle';
patch(g, 'versionCode 1\n',
  'versionCode System.getenv("VERSION_CODE") ? System.getenv("VERSION_CODE").toInteger() : 1\n');
patch(g, 'versionName "1.0"\n',
  'versionName "1.0." + (System.getenv("VERSION_CODE") ?: "0")\n');

// 3) fixed signing key: every build is signed with keystore/pilgercut.jks,
//    so updates install over the old app and the saved history is kept
patch(g, `    buildTypes {
        release {`,
`    signingConfigs {
        release {
            storeFile file("../../keystore/pilgercut.jks")
            storePassword "pilgercut2026"
            keyAlias "pilgercut"
            keyPassword "pilgercut2026"
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release`);

console.log('Android project patched.');
