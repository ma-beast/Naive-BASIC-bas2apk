# NaiveWORK 0.2G

NaiveWORK 0.2G — автономный Android runtime для программ, созданных NaiveBASIC bas2apk.

## Главное правило совместимости

Интерпретатор `BasicInterpreter.java` в этом проекте взят **без изменений** из NaiveBASIC 0.9.3G-BETA.

`BasicScreenView.java` также взят без изменений из NaiveBASIC 0.9.3G-BETA.

NaiveWORK не использует старый исходный код NaiveWORK 0.1G и не является бинарной переделкой старого APK.

При запуске создаётся новый экземпляр `BasicInterpreter`, которому передаётся содержимое `assets/programs/program.bas`.

## Состояние BASIC

Интерпретатор создаётся заново при каждом запуске приложения. Переменные, массивы, DATA/READ, GOSUB/FOR и состояние программы находятся в экземпляре `BasicInterpreter` и не сохраняются между запусками.

Файл `program.bas` является частью APK. NBM-файлы могут храниться во внутреннем хранилище приложения по правилам NaiveBASIC 0.9.3G-BETA.

## Версия

NaiveWORK 0.2G, versionCode 2.
