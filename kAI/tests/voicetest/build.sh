# compiles the kAI voice bench by hand (javac + dx) against the kAI build; output: ~/tts5/voicetest.jar
set -e
A=~/android/lineage-14.1
cd $A
K=$(ls -d out/target/common/obj/APPS/kAI_intermediates)
J=out/target/common/obj/JAVA_LIBRARIES
FW=$J/core-oj_intermediates/classes.jar:$J/core-libart_intermediates/classes.jar:$J/framework_intermediates/classes.jar
W=/tmp/voicetest; rm -rf $W; mkdir -p $W/classes
S=packages/apps/KiroOS/kAI/src
javac -source 1.7 -target 1.7 -bootclasspath $FW -d $W/classes \
  packages/apps/KiroOS/kAI/tests/voicetest/VoiceTest.java packages/apps/KiroOS/kAI/tests/voicetest/WavSay.java \
  $S/com/kiroos/kai/KaiSpeech.java \
  $S/com/k2fsa/sherpa/onnx/*.java 2>&1 | grep -v warning || true
DX=$(ls out/host/linux-x86/bin/dx prebuilts/sdk/tools/dx 2>/dev/null | head -1)
mkdir -p ~/voicetest; $DX --dex --output=$HOME/voicetest/voicetest.jar $W/classes
ls -la ~/voicetest/voicetest.jar
cp ~/voicetest/voicetest.jar /mnt/c/Users/Nicholas/.gemini/antigravity/scratch/android-s6-os/output/tts5/
