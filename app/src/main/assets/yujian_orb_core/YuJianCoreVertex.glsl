#version 300 es
precision highp float;
layout(location = 0) in vec2 aPosition;
uniform vec2 uSphereCenter;
uniform vec2 uQuadExtent;
out vec2 vNdc;
void main() {
    vNdc = uSphereCenter + aPosition * uQuadExtent;
    gl_Position = vec4(vNdc, 0.0, 1.0);
}
