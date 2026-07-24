# تل‌پلیر 🎵 — موزیک‌پلیر تلگرام

## راه ۱: Android Studio
1. منوی File > Open → پوشه TelPlayer
2. اگر پیام Gradle Wrapper آمد → Use default gradle wrapper
3. بعد از Sync → منوی Build > Build APK(s)

## راه ۲: GitHub Actions (بدون نصب هیچی)
1. در github.com یک ریپازیتوری جدید بساز
2. محتویات پوشه TelPlayer را آپلود کن (خود پوشه، نه زیپ)
3. فایل .github/workflows/build.yml داخل خود پروژه است
4. تب Actions → Build APK → دانلود از بخش Artifacts

## نکات
- اندروید ۱۱ به بالا: برای اسکن خودکار، دسترسی All Files Access لازم است
- دکمه «📁 پوشه» همیشه کار می‌کند (انتخاب دستی)
- ویس‌های تلگرام (.ogg) خودکار حذف می‌شوند
- پلی‌لیست‌ها دائمی ذخیره می‌شوند

## نسخه ۲ (به‌زودی)
- پخش در پس‌زمینه با Foreground Service
- اعلان و کنترل از لاک‌اسکرین
