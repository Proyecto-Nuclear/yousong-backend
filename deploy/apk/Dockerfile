# syntax=docker/dockerfile:1
# Android APK builder. Build context: the yousong-movile repository; the extra context "deploy" is this folder.
# Everything the build needs (Android SDK, Gradle distribution, Maven dependencies) is downloaded while the IMAGE is
# built, so building the APK later for another IP needs no internet. Several GB: build it at home.
FROM eclipse-temurin:21-jdk-noble

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/opt/gradle-home \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:$PATH

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip qrencode \
    && rm -rf /var/lib/apt/lists/*

# Android command-line tools (sdkmanager), then the platform and build tools the app compiles with.
ARG CMDLINE_TOOLS_VERSION=13114758
RUN curl -fsSL -o /tmp/tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
    && mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && unzip -q /tmp/tools.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/tools.zip \
    && (yes | sdkmanager --licenses > /dev/null) \
    && sdkmanager --install "platforms;android-37.0" "build-tools;36.0.0" > /dev/null

WORKDIR /src
COPY . .
# The repository pins the Gradle daemon to a JetBrains runtime (Android Studio); here the image JDK runs it.
# local.properties points to the developer's SDK and must not be used.
RUN rm -f gradle/gradle-daemon-jvm.properties local.properties \
    && find app gradle build.gradle.kts settings.gradle.kts gradle.properties -type f -print0 \
        | sort -z | xargs -0 sha256sum | sha256sum | cut -c1-16 > /opt/source-id \
    && ./gradlew --no-daemon --console=plain assembleDebug -PyousongLanHost=10.0.0.1 \
    && rm -rf app/build build .gradle /root/.android/*.lock

COPY --from=deploy build-apk.sh /usr/local/bin/build-apk.sh
COPY --from=deploy index.html.template /opt/index.html.template
RUN chmod 755 /usr/local/bin/build-apk.sh

# /root/.android keeps the debug signing key (a volume, so reinstalls over an older APK keep working).
VOLUME ["/root/.android"]
ENTRYPOINT ["/usr/local/bin/build-apk.sh"]
