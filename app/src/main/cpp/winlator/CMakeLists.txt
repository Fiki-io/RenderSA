cmake_minimum_required(VERSION 3.22.1)

Project(Winlator)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -Wno-unused-function -Wno-incompatible-pointer-types-discards-qualifiers -Wno-deprecated-declarations -Wimplicit-function-declaration")

include_directories(include)

add_library(winlator SHARED
            src/drawable.c
            src/gpu_image.c
            src/gpu_helper.c
            src/sysvshared_memory.c
            src/xconnector_epoll.c
            src/xinput_stream.c
            src/xoutput_stream.c
            src/arrays.c
            src/ring_buffer.c
            src/wine_registry_editor.c
            src/native_aaudio.c
            src/native_input_udp.c
            src/native_cpu_affinity.c
            src/native_crash_handler.c)

target_link_libraries(winlator
                      log
                      android
                      aaudio
                      jnigraphics
                      EGL
                      GLESv2
                      GLESv3)