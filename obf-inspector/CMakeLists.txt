project(inspector)

if (CMAKE_SHARED_LIBS_ALLOWED_ON_TARGET)
	add_executable(inspector
		"main.cpp"
	)
	add_dependencies(inspector
		OsmAndCoreTools_shared
		OsmAndCore_ResourcesBundle_shared
	)
	target_link_libraries(inspector
		OsmAndCoreTools_shared
		OsmAndCore_ResourcesBundle_shared
	)
endif()

if (TARGET OsmAndCoreTools_static_standalone)
	add_executable(inspector_standalone
		"main.cpp"
	)
	add_dependencies(inspector_standalone
		OsmAndCoreTools_static_standalone
		OsmAndCore_ResourcesBundle_static
	)
	target_link_libraries(inspector_standalone
		OsmAndCoreTools_static_standalone
	)
	link_entire_static_library(inspector_standalone OsmAndCore_ResourcesBundle_static)
endif()
