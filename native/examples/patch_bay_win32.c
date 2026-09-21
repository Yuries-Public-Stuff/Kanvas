#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>

#define ID_ADD 101
#define ID_REMOVE 102
#define ID_HOLD 103
#define ID_RESET 104
#define ID_COMPACT 105
#define ID_TIMER 1

static HWND title_text, section_text, queue_text, detail_text, hold_text, compact_button, hold_button, metrics_text;
static int queued = 3, paused = 0, compact = 0;
static LARGE_INTEGER frequency, sample_start, last_paint;
static unsigned paints = 0;
static double paint_ms = 0.0, total_paint_ms = 0.0;
static HFONT font;

static HWND control(HWND parent, const char *kind, const char *label, DWORD style, int id) {
    HWND result = CreateWindowExA(0, kind, label, WS_CHILD | WS_VISIBLE | style,
        0, 0, 1, 1, parent, (HMENU)(INT_PTR)id, GetModuleHandleA(NULL), NULL);
    SendMessageA(result, WM_SETFONT, (WPARAM)font, TRUE);
    return result;
}

static void refresh(void) {
    char buffer[128];
    snprintf(buffer, sizeof(buffer), "Items in queue: %d", queued);
    SetWindowTextA(queue_text, buffer);
    SetWindowTextA(hold_text, paused ? "Status: On hold" : "Status: Running");
    SetWindowTextA(hold_button, paused ? "Resume" : "Hold");
    SetWindowTextA(compact_button, compact ? "Full view" : "Compact view");
    SetWindowTextA(detail_text, compact ? "" : "Nothing important is happening here.");
}

static void layout(HWND window) {
    RECT rect;
    GetClientRect(window, &rect);
    int width = rect.right, height = rect.bottom;
    int content = width - 48;
    if (content < 100) content = 100;
    MoveWindow(title_text, 24, 20, content, 40, TRUE);
    MoveWindow(section_text, 24, 76, content, 30, TRUE);
    MoveWindow(queue_text, 24, 116, content, 32, TRUE);
    MoveWindow(hold_text, 24, 150, content, 30, TRUE);
    MoveWindow(detail_text, 24, 192, content, 26, TRUE);
    HWND buttons[] = {GetDlgItem(window, ID_ADD), GetDlgItem(window, ID_REMOVE), hold_button, GetDlgItem(window, ID_RESET)};
    int available = content < 700 ? content : 700;
    int gap = 10, button_width = (available - 3 * gap) / 4;
    for (int i = 0; i < 4; ++i) MoveWindow(buttons[i], 24 + i * (button_width + gap), 242, button_width, 42, TRUE);
    MoveWindow(compact_button, 24, 300, 180, 38, TRUE);
    MoveWindow(metrics_text, 24, height > 390 ? height - 46 : 350, content, 26, TRUE);
}

static LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
    case WM_CREATE: {
        HFONT stock = (HFONT)GetStockObject(DEFAULT_GUI_FONT);
        font = stock;
        title_text = control(window, "STATIC", "Queue Desk", SS_LEFT, 0);
        section_text = control(window, "STATIC", "Local workspace / Job queue", SS_LEFT, 0);
        queue_text = control(window, "STATIC", "", SS_LEFT, 0);
        hold_text = control(window, "STATIC", "", SS_LEFT, 0);
        detail_text = control(window, "STATIC", "", SS_LEFT, 0);
        control(window, "BUTTON", "Add", BS_PUSHBUTTON, ID_ADD);
        control(window, "BUTTON", "Remove", BS_PUSHBUTTON, ID_REMOVE);
        hold_button = control(window, "BUTTON", "Hold", BS_PUSHBUTTON, ID_HOLD);
        control(window, "BUTTON", "Reset", BS_PUSHBUTTON, ID_RESET);
        compact_button = control(window, "BUTTON", "Compact view", BS_PUSHBUTTON, ID_COMPACT);
        metrics_text = control(window, "STATIC", "Paint rate: sampling...", SS_LEFT, 0);
        QueryPerformanceFrequency(&frequency);
        QueryPerformanceCounter(&sample_start);
        last_paint = sample_start;
        refresh();
        SetTimer(window, ID_TIMER, 16, NULL);
        return 0;
    }
    case WM_SIZE: layout(window); return 0;
    case WM_COMMAND:
        if (HIWORD(wparam) != BN_CLICKED) break;
        switch (LOWORD(wparam)) {
        case ID_ADD: if (queued < 99) ++queued; break;
        case ID_REMOVE: if (queued > 0) --queued; break;
        case ID_HOLD: paused = !paused; break;
        case ID_RESET: queued = 3; paused = 0; compact = 0; break;
        case ID_COMPACT: compact = !compact; break;
        default: break;
        }
        refresh();
        return 0;
    case WM_TIMER:
        if (wparam == ID_TIMER) {
            RECT bounds;
            GetClientRect(window, &bounds);
            bounds.top = bounds.bottom > 390 ? bounds.bottom - 50 : 345;
            InvalidateRect(window, &bounds, FALSE);
            return 0;
        }
        break;
    case WM_ERASEBKGND: {
        RECT rect;
        GetClientRect(window, &rect);
        FillRect((HDC)wparam, &rect, (HBRUSH)(COLOR_BTNFACE + 1));
        return 1;
    }
    case WM_PAINT: {
        LARGE_INTEGER begin, now;
        QueryPerformanceCounter(&begin);
        PAINTSTRUCT paint;
        BeginPaint(window, &paint);
        EndPaint(window, &paint);
        QueryPerformanceCounter(&now);
        paint_ms = 1000.0 * (double)(now.QuadPart - last_paint.QuadPart) / (double)frequency.QuadPart;
        total_paint_ms += 1000.0 * (double)(now.QuadPart - begin.QuadPart) / (double)frequency.QuadPart;
        last_paint = now;
        ++paints;
        double elapsed = (double)(now.QuadPart - sample_start.QuadPart) / (double)frequency.QuadPart;
        if (elapsed >= 1.0) {
            char buffer[160];
            snprintf(buffer, sizeof(buffer), "PAINTS/S %.1f  MAIN %.3f MS  INTERVAL %.1f MS (NOT GPU FPS)",
                paints / elapsed, total_paint_ms / paints, paint_ms);
            SetWindowTextA(metrics_text, buffer);
            sample_start = now;
            paints = 0;
            total_paint_ms = 0.0;
        }
        return 0;
    }
    case WM_DESTROY: KillTimer(window, ID_TIMER); PostQuitMessage(0); return 0;
    }
    return DefWindowProcA(window, message, wparam, lparam);
}

int main(void) {
    HINSTANCE instance = GetModuleHandleA(NULL);
    WNDCLASSA klass = {0};
    klass.hInstance = instance;
    klass.lpfnWndProc = window_proc;
    klass.lpszClassName = "QueueDeskWin32Baseline";
    klass.hCursor = LoadCursor(NULL, IDC_ARROW);
    klass.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
    if (!RegisterClassA(&klass)) return 1;
    HWND window = CreateWindowExA(0, klass.lpszClassName, "Queue Desk | Windows controls",
        WS_OVERLAPPEDWINDOW | WS_VISIBLE, CW_USEDEFAULT, CW_USEDEFAULT, 960, 540,
        NULL, NULL, instance, NULL);
    if (!window) return 2;
    MSG message;
    while (GetMessageA(&message, NULL, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageA(&message);
    }
    return (int)message.wParam;
}
