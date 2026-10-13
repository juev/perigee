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

Screenshots are not there yet.
