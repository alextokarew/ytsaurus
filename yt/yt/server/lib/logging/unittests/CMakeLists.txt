
# This file was generated by the build system used internally in the Yandex monorepo.
# Only simple modifications are allowed (adding source-files to targets, adding simple properties
# like target_include_directories). These modifications will be ported to original
# ya.make files by maintainers. Any complex modifications which can't be ported back to the
# original buildsystem will not be accepted.



add_executable(unittester-library-logging)
target_compile_options(unittester-library-logging PRIVATE
  -Wdeprecated-this-capture
)
target_link_libraries(unittester-library-logging PUBLIC
  contrib-libs-linux-headers
  contrib-libs-cxxsupp
  yutil
  library-cpp-cpuid_check
  cpp-testing-gtest
  cpp-testing-gtest_main
  server-lib-logging
  yt-yt-client
  yt-yt-core
)
target_link_options(unittester-library-logging PRIVATE
  -ldl
  -lrt
  -Wl,--no-as-needed
  -fPIC
  -fPIC
  -lpthread
  -lrt
  -ldl
  -lutil
)
target_sources(unittester-library-logging PRIVATE
  ${CMAKE_SOURCE_DIR}/yt/yt/server/lib/logging/unittests/category_registry_ut.cpp
)
add_test(
  NAME
  unittester-library-logging
  COMMAND
  unittester-library-logging
)
set_property(
  TEST
  unittester-library-logging
  PROPERTY
  LABELS
  SMALL
)
set_property(
  TEST
  unittester-library-logging
  PROPERTY
  PROCESSORS
  1
)
target_allocator(unittester-library-logging
  cpp-malloc-tcmalloc
  libs-tcmalloc-default
)
vcs_info(unittester-library-logging)
