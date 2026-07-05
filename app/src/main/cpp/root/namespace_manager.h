#pragma once

/**
 * LD_PRELOAD injection engine — replaces mount-namespace approach.
 *
 * setup_ld_preload_injection():
 *   - Deploys a fake-su shell script to root_dir
 *   - Sets LD_PRELOAD for all child processes (popen/exec/Runtime.exec)
 *   - dlopen's preload_lib_path into the host process (RTLD_GLOBAL) so
 *     guest native libs loaded afterwards see our symbol overrides
 *
 * Returns 0 on success, negative on fatal error.
 * Non-fatal: dlopen failure is logged but child-process injection still works.
 */
int  setup_ld_preload_injection(const char* root_dir, const char* preload_lib_path);
void teardown_ld_preload_injection();
bool is_injection_active();
