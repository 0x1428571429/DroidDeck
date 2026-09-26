#ifndef SINGLE_PIXEL_BUFFER_V1_SERVER_PROTOCOL_H
#define SINGLE_PIXEL_BUFFER_V1_SERVER_PROTOCOL_H

#include <stdint.h>
#include "wayland-server.h"

#ifdef __cplusplus
extern "C" {
#endif

struct wp_single_pixel_buffer_manager_v1_interface {
    void (*destroy)(struct wl_client *client, struct wl_resource *resource);
    void (*create_u32_rgba_buffer)(struct wl_client *client, struct wl_resource *resource,
                                   uint32_t id, uint32_t red, uint32_t green,
                                   uint32_t blue, uint32_t alpha);
};

extern const struct wl_interface wp_single_pixel_buffer_manager_v1_interface;

#ifdef __cplusplus
}
#endif

#endif
