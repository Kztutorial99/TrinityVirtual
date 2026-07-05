#pragma once

void install_su_interceptor(const char* su_path);
void uninstall_su_interceptor();
bool is_su_interceptor_active();
