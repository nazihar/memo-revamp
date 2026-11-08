/** Platform operator console: tenant registry, provisioning and suspension (ADR-0031). Never reads tenant data. */
@ApplicationModule(displayName = "Platform console")
@NullMarked
package com.memo.platform;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
