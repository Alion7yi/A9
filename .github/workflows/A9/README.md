# لانچر جی‌پلاس (Android TV 9)

## ساخت APK بدون نصب Android Studio (پیشنهادی)
1. یک ریپوی خالی در GitHub بسازید و محتویات این پوشه را در آن آپلود کنید.
2. به تب **Actions** بروید ← workflow به نام **Build APK** (خودکار اجرا می‌شود؛ یا Run workflow).
3. بعد از حدود ۵ تا ۱۰ دقیقه، از پایین صفحه‌ی همان اجرا فایل **GPlus-Launcher-APK** را دانلود کنید (داخلش app-debug.apk است).

## ساخت با Android Studio
پوشه را Open کنید، Sync بزنید، سپس Build > Build APK.

## نصب روی تلویزیون
TV: تنظیمات ← درباره ← چند بار روی Build Number ← گزینه‌های توسعه‌دهنده ← USB/Network debugging
```
adb connect IP_TV:5555
adb install -r app-debug.apk
adb shell cmd package set-home-activity ir.gplus.launcher/.MainActivity
```
بار اول روی تلویزیون پیام اجازه‌ی «خواندن کانال‌ها» می‌آید؛ حتماً Allow بزنید (برای پخش آنتن لازم است).

## سفارشی‌سازی
- پس‌زمینه: عکس را با نام `bg.jpg` در `app/src/main/res/drawable/` بگذارید.
- لوگو: فایل `logo.png` در همان پوشه.
- شهر آب‌وهوا: مختصات در `Data.kt` (تابع fetchWeather).
- برنامه‌های ثابت ردیف محبوب: لیست `PINNED` در `Data.kt`.
- صدای پیش‌نمایش داخل کادر: `PREVIEW_VOLUME` در `Ui.kt`.
- فونت: فایل ttf (مثلاً وزیرمتن) در `res/font` و تغییر `AppFont`.

## کنترل
- OK روی کادر تلویزیون: تمام‌صفحه؛ داخل تمام‌صفحه OK = انتخاب ورودی، بالا/پایین یا CH = تغییر کانال، Back = برگشت.
- دکمه Home از هر جا: برگشت به صفحه‌ی اصلی.
