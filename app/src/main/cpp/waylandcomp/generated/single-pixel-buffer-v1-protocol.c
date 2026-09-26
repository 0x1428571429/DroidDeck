/* Protocol description for wp_single_pixel_buffer_manager_v1. */

#include <stddef.h>
#include "wayland-util.h"

extern const struct wl_interface wl_buffer_interface;

static const struct wl_interface *single_pixel_buffer_types[] = {
    &wl_buffer_interface,
    NULL,
    NULL,
    NULL,
    NULL,
};

static const struct wl_message single_pixel_buffer_requests[] = {
    { "destroy", "", NULL },
    { "create_u32_rgba_buffer", "nuuuu", single_pixel_buffer_types },
};

WL_EXPORT const struct wl_interface wp_single_pixel_buffer_manager_v1_interface = {
    "wp_single_pixel_buffer_manager_v1", 1,
    2, single_pixel_buffer_requests,
    0, NULL,
};
