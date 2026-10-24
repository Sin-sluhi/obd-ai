#!/bin/sh
# Сборка OBD AI для Windows встроенным компилятором C# (.NET Framework 4.8, есть в Windows 10/11).
# x86 — основная: драйверы J2534 (Scanmatik и др.) 32-битные.
set -e
cd "$(dirname "$0")"
python prep_catalog.py
CSC=/c/Windows/Microsoft.NET/Framework/v4.0.30319/csc.exe
SRC="App.cs J2534.cs Scan.cs Names.cs Catalog.cs Verdict.cs Theme.cs MainForm.cs ResultView.cs Report.cs Sim.cs"
for P in x86 x64; do
  "$CSC" -nologo -optimize+ -platform:$P -target:winexe -codepage:65001 \
    -r:System.Web.Extensions.dll -r:System.Windows.Forms.dll -r:System.Drawing.dll \
    -resource:out/catalog.json.gz,catalog.json.gz \
    -out:"out/OBD-AI-$P.exe" $SRC
done
ls -la out
