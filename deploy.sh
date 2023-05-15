./gradlew installDist -x checkLicense
REV=`git rev-parse --short HEAD`
cp -R build/install/teku teku-beku-$REV
tar cvf teku-beku-$REV.tar.gz teku-beku-$REV
scp teku-beku-$REV.tar.gz 3.145.211.75:teku-beku-$REV.tar.gz
