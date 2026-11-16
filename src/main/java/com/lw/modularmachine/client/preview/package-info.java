/**
 * Internal client preview UI and render resources. Public scene/camera/render
 * contracts live in api.client.preview. ClientMachineCatalog owns server
 * display generations and disconnect cleanup for blueprints, JEI and EMI.
 * Display state owns the renderer; SectionMeshCache owns builds/pages and its
 * transparent batch. Model capture and GPU work stay on the render thread;
 * packing/sorting use detached arrays. Close, replacement and resource reload
 * release the existing owned resources.
 */
@ApiStatus.Internal
package com.lw.modularmachine.client.preview;

import org.jetbrains.annotations.ApiStatus;
