# Store graphics

The pictures the stores show, in `fastlane/metadata/android/en-US/images`, are drawn from the two pages here with the shapes of the launcher icon (`app/src/main/res/drawable/ic_launcher_foreground.xml`). A change to the icon is made in all three.

To draw them again, with Chrome:

```sh
chrome --headless=new --hide-scrollbars --force-device-scale-factor=1 --window-size=512,512 \
    --screenshot=fastlane/metadata/android/en-US/images/icon.png docs/design/store-icon.html
chrome --headless=new --hide-scrollbars --force-device-scale-factor=1 --window-size=1024,500 \
    --screenshot=fastlane/metadata/android/en-US/images/featureGraphic.png docs/design/store-feature-graphic.html
```

Colours: terracotta `#B4532A`, paper `#FBF5EA`, ink `#2E2620`. The screens of the app use the same paper and ink and an accent a shade darker, `#A04722`; see `app/src/main/kotlin/org/evsyukov/perigee/ui/Theme.kt`.

## Phone screenshots

`phoneScreenshots/1.png` to `6.png`: a page of a book, the library as covers, the menu over a page, the reading settings, a page at night, the page about a book. They were taken on the emulator `shareding_api36` (Android 16, 420 dpi) on 2026-10-14, with the screen set to 1080×2160: Google Play takes no picture whose long side is more than twice the short one, and the emulator's own 2400 is.

```sh
adb shell wm size 1080x2160
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1000
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile hide
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
adb exec-out screencap -p > 1.png
adb shell am broadcast -a com.android.systemui.demo -e command exit
adb shell wm size reset
```

Reading settings as shown: Roboto 18, line spacing 120 %, justified with hyphenation, page margins on.

The books are the Standard Ebooks editions (standardebooks.org), downloaded 2026-10-14. Standard Ebooks dedicates its own work to the public domain under CC0 1.0 and holds the text and cover art of its books to be in the U.S. public domain. The covers are adapted from these paintings:

| Book | Painting |
|---|---|
| Pride and Prejudice | Edmund Blair Leighton, *A Wet Sunday Morning*, 1896 |
| Frankenstein | Giuseppe Arcimboldo, *Portrait of Adam*, 1578 |
| Alice’s Adventures in Wonderland | Ivan Shishkin, *Amanita*, 1879 |
| Anna Karenina | John Singer Sargent, *Elizabeth Winthrop Chanler*, 1893 |
| Moby Dick | Albert Pinkham Ryder, *The Flying Dutchman*, 1887 |
| Crime and Punishment | Edvard Munch, *Self-Portrait with Burning Cigarette*, 1895 |
| The Adventures of Sherlock Holmes | Thomas Eakins, *Music*, 1904 |

Tablet screenshots are not there yet.
