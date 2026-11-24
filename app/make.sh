#!/bin/bash

cd $(dirname $0)

./gradlew :app:assembleDebug
#./gradlew :app:assembleRelease
