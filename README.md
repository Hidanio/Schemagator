
This plugin can solve the problem with JSON-schemas: Jetbrains IDE can not work with windows-1251 encodings and converting files to UTF-8 does not guarantee that it will work correct in a lot of cases (Jetbrains used old java converter(Jackson))

This plugin fixed all issues with custom Json schemas. More about bugs - [here](https://youtrack.jetbrains.com/issue/IJPL-202215/Navigation-problem-with-custom-json-schema).

How to install?
1. Download archive from release
2. In IDE: Settings-> Plugins -> Gear -> install from Disk -> select your archive
