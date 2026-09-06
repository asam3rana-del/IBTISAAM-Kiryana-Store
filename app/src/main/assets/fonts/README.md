# Urdu font goes here

Place the font file **exactly** here, named exactly like this:

    app/src/main/assets/fonts/NotoNastaliqUrdu-Regular.ttf

Download it (free, open-license) from Google Fonts:
https://fonts.google.com/noto/specimen/Noto+Nastaliq+Urdu
-> "Download family" -> unzip -> copy `NotoNastaliqUrdu-Regular.ttf` into this folder.

Once the file is present, PrinterHelper.resolveUrduTypeface() will pick it up
automatically on the next build — no code change needed. Until then, receipts
still print correctly (Android auto-substitutes an Arabic-capable system font
for any Urdu characters), just in Naskh style instead of Nastaliq, and only on
devices that actually ship an Arabic system font (see PrinterHelper.kt for the
fallback chain that was added to make this deterministic across POS devices).
