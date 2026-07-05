#pragma once
#include <string>

std::string read_file(const std::string& path);
bool write_file(const std::string& path, const std::string& content);
