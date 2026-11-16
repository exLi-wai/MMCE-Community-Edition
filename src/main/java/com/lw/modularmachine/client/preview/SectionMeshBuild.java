package com.lw.modularmachine.client.preview;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;

/**
 * One cache-owned capture, packing and partial GPU upload; close releases
 * unadopted pages.
 */
@ApiStatus.Internal
final class SectionMeshBuild implements AutoCloseable {
  final PreviewRenderScene.Section section;
  final long revision;
  final Vec3 origin;
  final List<BlockState> states;
  final List<PreviewMeshData.Quad> quads = new ArrayList<>();
  final List<SectionMeshPage> uploaded = new ArrayList<>();
  final RandomSource random = RandomSource.create();
  int cursor, uploadCursor, bytes;
  boolean modelSensitive;
  CompletableFuture<List<PreviewMeshData.Page>> packing;
  List<PreviewMeshData.Page> packed;
  SectionMeshBuild(PreviewRenderScene.Section section, long revision, List<BlockState> states) {
    this.section = section;
    this.revision = revision;
    this.states = states;
    var pos = SectionPos.of(section.spatialKey()).origin();
    origin = Vec3.atLowerCornerOf(pos);
  }
  @Override
  public void close() {
    uploaded.forEach(SectionMeshPage::close);
    uploaded.clear();
    quads.clear();
    packed = null;
  }
}
