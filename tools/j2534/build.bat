@echo off
rem Сборка OBIDI J2534 Probe компилятором C# из .NET Framework 4.8 (есть в Windows 10/11)
set CSC=%WINDIR%\Microsoft.NET\Framework4.0.30319\csc.exe
if not exist out mkdir out
%CSC% -nologo -optimize+ -platform:x86 -out:out\ObidiJ2534-x86.exe ObidiJ2534.cs
%CSC% -nologo -optimize+ -platform:x64 -out:out\ObidiJ2534-x64.exe ObidiJ2534.cs
