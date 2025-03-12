@echo off

REM Set the name of your Java file (e.g., App.java)
set JAVA_FILE=App.java

REM Set the name of the class containing the main method (e.g., com.app.App)
set CLASS_NAME=com.app.App

REM Compile the Java file
echo Compiling %JAVA_FILE%...
javac -d . %JAVA_FILE%
if %errorlevel% neq 0 goto :end

REM Run the compiled program with arguments
echo Running %CLASS_NAME% with arguments provided in command line
java %CLASS_NAME% %*

:end
echo Done.
pause