
# This file was generated by the build system used internally in the Yandex monorepo.
# Only simple modifications are allowed (adding source-files to targets, adding simple properties
# like target_include_directories). These modifications will be ported to original
# ya.make files by maintainers. Any complex modifications which can't be ported back to the
# original buildsystem will not be accepted.



add_library(server-lib-rpc_proxy)
target_compile_options(server-lib-rpc_proxy PRIVATE
  -Wdeprecated-this-capture
)
target_link_libraries(server-lib-rpc_proxy PUBLIC
  contrib-libs-linux-headers
  contrib-libs-cxxsupp
  yutil
  yt-yt-ytlib
  yt-library-auth_server
  yt-yt-client
  yt-client-arrow
  server-lib-misc
  yt-library-error_skeleton
  server-lib-transaction_server
  yt-yt-core
)
target_sources(server-lib-rpc_proxy PRIVATE
  ${CMAKE_SOURCE_DIR}/yt/yt/server/lib/rpc_proxy/access_checker.cpp
  ${CMAKE_SOURCE_DIR}/yt/yt/server/lib/rpc_proxy/api_service.cpp
  ${CMAKE_SOURCE_DIR}/yt/yt/server/lib/rpc_proxy/config.cpp
  ${CMAKE_SOURCE_DIR}/yt/yt/server/lib/rpc_proxy/format_row_stream.cpp
  ${CMAKE_SOURCE_DIR}/yt/yt/server/lib/rpc_proxy/helpers.cpp
  ${CMAKE_SOURCE_DIR}/yt/yt/server/lib/rpc_proxy/proxy_coordinator.cpp
  ${CMAKE_SOURCE_DIR}/yt/yt/server/lib/rpc_proxy/security_manager.cpp
)
