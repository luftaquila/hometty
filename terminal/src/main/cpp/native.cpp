#include <jni.h>
#include <vterm.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <signal.h>
#include <poll.h>
#include <termios.h>
#include <algorithm>
#include <cstdlib>
#include <cerrno>
#include <string>
#include <vector>
#include <deque>

// dropped counts history lines discarded at the limit, so dropped + history index + screen row
// numbers every line from the start of the session.
// cells counts the cells the history holds, which bounds its memory however wide lines are.
struct Screen { VTerm* term; VTermScreen* screen; std::deque<std::vector<VTermScreenCell>> history; int limit; long dropped; size_t cells; };
static Screen* screen(jlong p) { return reinterpret_cast<Screen*>(p); }
// A blank cell in the default colors, for columns past a saved line's width. A zeroed cell would
// be RGB black and zero columns wide, which stalls libvterm's resize loop over popped lines.
static VTermScreenCell blank(Screen* s) {
    VTermScreenCell cell{}; cell.width=1;
    vterm_state_get_default_colors(vterm_obtain_state(s->term),&cell.fg,&cell.bg);
    return cell;
}
// About 80 MB of saved cells at most.
static const size_t MAX_CELLS = 2000000;
// A line is saved without its trailing blank cells; reading past its end gives blank cells.
static int pushline(int cols, const VTermScreenCell* cells, void* user) {
    auto s = static_cast<Screen*>(user);
    int width = cols;
    while (width > 0 && cells[width-1].chars[0] == 0 && !cells[width-1].attrs.reverse && VTERM_COLOR_IS_DEFAULT_BG(&cells[width-1].bg)) width--;
    s->history.emplace_back(cells,cells+width); s->cells += width;
    while (!s->history.empty() && (s->history.size() > static_cast<size_t>(s->limit) || s->cells > MAX_CELLS)) {
        s->cells -= s->history.front().size(); s->history.pop_front(); s->dropped++;
    }
    return 1;
}
static int popline(int cols, VTermScreenCell* cells, void* user) {
    auto s = static_cast<Screen*>(user); if(s->history.empty()) return 0;
    auto row = s->history.back(); s->history.pop_back(); s->cells -= row.size();
    for(int i=0;i<cols;i++) cells[i] = i < static_cast<int>(row.size()) ? row[i] : blank(s);
    return 1;
}
static VTermScreenCallbacks callbacks = {nullptr,nullptr,nullptr,nullptr,nullptr,nullptr,pushline,popline,nullptr};
extern "C" JNIEXPORT jlong JNICALL Java_io_luftaquila_hometty_terminal_Native_create(JNIEnv*, jobject, jint rows,jint cols,jint limit) {
    auto s = new Screen{}; s->term=vterm_new(rows,cols); s->limit=limit;
    vterm_set_utf8(s->term,1); s->screen=vterm_obtain_screen(s->term);
    vterm_screen_enable_altscreen(s->screen,1); vterm_screen_set_callbacks(s->screen,&callbacks,s); vterm_screen_reset(s->screen,1);
    return reinterpret_cast<jlong>(s);
}
extern "C" JNIEXPORT void JNICALL Java_io_luftaquila_hometty_terminal_Native_destroy(JNIEnv*,jobject,jlong ptr) { auto s=screen(ptr); vterm_free(s->term); delete s; }
extern "C" JNIEXPORT void JNICALL Java_io_luftaquila_hometty_terminal_Native_feed(JNIEnv* env,jobject,jlong ptr,jbyteArray data) {
    auto bytes=env->GetByteArrayElements(data,nullptr); auto s=screen(ptr);
    vterm_input_write(s->term,reinterpret_cast<char*>(bytes),env->GetArrayLength(data)); vterm_screen_flush_damage(s->screen);
    env->ReleaseByteArrayElements(data,bytes,JNI_ABORT);
}
static jbyteArray drain(JNIEnv* env,Screen* s) {
    size_t count=vterm_output_get_buffer_current(s->term); std::vector<char> bytes(count);
    vterm_output_read(s->term,bytes.data(),count); auto result=env->NewByteArray(count);
    env->SetByteArrayRegion(result,0,count,reinterpret_cast<jbyte*>(bytes.data())); return result;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_luftaquila_hometty_terminal_Native_output(JNIEnv* env,jobject,jlong ptr) { return drain(env,screen(ptr)); }
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_luftaquila_hometty_terminal_Native_key(JNIEnv* env,jobject,jlong ptr,jint key,jint modifiers) {
    auto s=screen(ptr); vterm_keyboard_key(s->term,static_cast<VTermKey>(key),static_cast<VTermModifier>(modifiers)); return drain(env,s);
}
extern "C" JNIEXPORT void JNICALL Java_io_luftaquila_hometty_terminal_Native_resize(JNIEnv*,jobject,jlong ptr,jint fd,jint rows,jint cols) {
    vterm_set_size(screen(ptr)->term,rows,cols); struct winsize size{}; size.ws_row=rows;size.ws_col=cols; if(fd>=0) ioctl(fd,TIOCSWINSZ,&size);
}
extern "C" JNIEXPORT jint JNICALL Java_io_luftaquila_hometty_terminal_Native_historySize(JNIEnv*,jobject,jlong ptr) { return screen(ptr)->history.size(); }
// {cursor row, cursor column, absolute number of screen row 0}.
extern "C" JNIEXPORT jlongArray JNICALL Java_io_luftaquila_hometty_terminal_Native_position(JNIEnv* env,jobject,jlong ptr) {
    auto s=screen(ptr); VTermPos cursor; vterm_state_get_cursorpos(vterm_obtain_state(s->term),&cursor);
    jlong values[3]={cursor.row,cursor.col,s->dropped+static_cast<jlong>(s->history.size())};
    auto result=env->NewLongArray(3); env->SetLongArrayRegion(result,0,3,values); return result;
}
// One code point per column of an absolute line (0 empty, -1 the right half of a wide character),
// or null when the line is no longer kept.
extern "C" JNIEXPORT jintArray JNICALL Java_io_luftaquila_hometty_terminal_Native_line(JNIEnv* env,jobject,jlong ptr,jlong absolute) {
    auto s=screen(ptr); int rows,cols; vterm_get_size(s->term,&rows,&cols);
    long index=absolute-s->dropped; long history=s->history.size();
    if(index<0 || index>=history+rows) return nullptr;
    std::vector<jint> line(cols);
    for(int c=0;c<cols;c++) {
        VTermScreenCell cell{};
        if(index<history) { const auto& row=s->history[index]; if(c<static_cast<int>(row.size())) cell=row[c]; }
        else vterm_screen_get_cell(s->screen,VTermPos{static_cast<int>(index-history),c},&cell);
        line[c]=cell.chars[0]==0xFFFFFFFF ? -1 : static_cast<jint>(cell.chars[0]);
    }
    auto result=env->NewIntArray(cols); env->SetIntArrayRegion(result,0,cols,line.data()); return result;
}
// Cells keep ANSI colors as palette indices and are converted when read, so a new palette also recolors existing output.
extern "C" JNIEXPORT void JNICALL Java_io_luftaquila_hometty_terminal_Native_palette(JNIEnv* env,jobject,jlong ptr,jintArray colors) {
    auto state=vterm_obtain_state(screen(ptr)->term); int n=std::min<int>(env->GetArrayLength(colors),16);
    std::vector<jint> rgb(n); env->GetIntArrayRegion(colors,0,n,rgb.data());
    for(int i=0;i<n;i++) { VTermColor col; vterm_color_rgb(&col,(rgb[i]>>16)&0xFF,(rgb[i]>>8)&0xFF,rgb[i]&0xFF); vterm_state_set_palette_color(state,i,&col); }
}
extern "C" JNIEXPORT jintArray JNICALL Java_io_luftaquila_hometty_terminal_Native_cells(JNIEnv* env,jobject,jlong ptr,jint offset) {
    auto s=screen(ptr); int rows,cols; vterm_get_size(s->term,&rows,&cols); std::vector<jint> cells(rows*cols*10+2);
    VTermPos cursor; vterm_state_get_cursorpos(vterm_obtain_state(s->term),&cursor); cells[0]=cursor.row;cells[1]=cursor.col;
    int history=s->history.size(); if(offset>history) offset=history;
    for(int r=0;r<rows;r++) for(int c=0;c<cols;c++) {
        VTermScreenCell cell=blank(s); int line=r-offset;
        if(line<0) { const auto& row=s->history[history+line]; if(c<static_cast<int>(row.size())) cell=row[c]; }
        else vterm_screen_get_cell(s->screen,VTermPos{line,c},&cell);
        int defaults=(VTERM_COLOR_IS_DEFAULT_FG(&cell.fg)?8:0)|(VTERM_COLOR_IS_DEFAULT_BG(&cell.bg)?16:0);
        vterm_screen_convert_color_to_rgb(s->screen,&cell.fg);vterm_screen_convert_color_to_rgb(s->screen,&cell.bg);
        int i=2+(r*cols+c)*10;
        // A cell's code points end at the first 0; reused scrollback buffers keep stale ones after it.
        bool ended=false;
        for(int cp=0;cp<6;cp++) { ended=ended || cell.chars[cp]==0; cells[i+cp]=ended || cell.chars[cp]==0xFFFFFFFF ? 0 : cell.chars[cp]; }
        cells[i+6]=0xFF000000|(cell.fg.rgb.red<<16)|(cell.fg.rgb.green<<8)|cell.fg.rgb.blue;
        cells[i+7]=0xFF000000|(cell.bg.rgb.red<<16)|(cell.bg.rgb.green<<8)|cell.bg.rgb.blue;
        cells[i+8]=(cell.attrs.bold?1:0)|(cell.attrs.underline?2:0)|(cell.attrs.reverse?4:0)|defaults; cells[i+9]=cell.width;
    }
    auto result=env->NewIntArray(cells.size());env->SetIntArrayRegion(result,0,cells.size(),cells.data());return result;
}
extern "C" JNIEXPORT jintArray JNICALL Java_io_luftaquila_hometty_terminal_Native_spawn(JNIEnv* env,jobject,jstring executable,jobjectArray arguments,jstring directory,jobjectArray environment,jint rows,jint cols,jboolean defer) {
    auto utf = [&](jstring text) { const char* p=env->GetStringUTFChars(text,nullptr);std::string result(p);env->ReleaseStringUTFChars(text,p);return result; };
    std::string exe=utf(executable),dir=utf(directory);std::vector<std::string> args,vars;
    for(int i=0;i<env->GetArrayLength(arguments);i++) { auto t=static_cast<jstring>(env->GetObjectArrayElement(arguments,i));args.push_back(utf(t));env->DeleteLocalRef(t); }
    for(int i=0;i<env->GetArrayLength(environment);i++) { auto t=static_cast<jstring>(env->GetObjectArrayElement(environment,i));vars.push_back(utf(t));env->DeleteLocalRef(t); }
    std::vector<char*> argv,envp;for(auto& a:args) argv.push_back(a.data());argv.push_back(nullptr);for(auto& v:vars)envp.push_back(v.data());envp.push_back(nullptr);
    int fd=posix_openpt(O_RDWR|O_NOCTTY|O_CLOEXEC);
    if(fd<0 || grantpt(fd)<0 || unlockpt(fd)<0) { if(fd>=0)close(fd);return nullptr; }
    char slaveName[256]; if(ptsname_r(fd,slaveName,sizeof(slaveName))!=0){close(fd);return nullptr;}
    // Canonical-mode programs must erase a complete UTF-8 character on DEL.
    struct termios attributes{};
    if(tcgetattr(fd,&attributes)==0) { attributes.c_iflag|=IUTF8;tcsetattr(fd,TCSANOW,&attributes); }
    struct winsize size{};size.ws_row=rows;size.ws_col=cols;ioctl(fd,TIOCSWINSZ,&size);
    // Prepare all strings before fork; child performs only async-signal-safe system calls.
    int gate[2]={-1,-1};if(defer && pipe2(gate,O_CLOEXEC)<0){close(fd);return nullptr;}
    int pid=fork();
    if(pid==0) {
        setsid(); int slave=open(slaveName,O_RDWR); if(slave<0)_exit(126); ioctl(slave,TIOCSCTTY,0);
        dup2(slave,0);dup2(slave,1);dup2(slave,2);if(slave>2)close(slave);close(fd);
        if(defer){close(gate[1]);char ready;ssize_t n;do{n=read(gate[0],&ready,1);}while(n<0 && errno==EINTR);close(gate[0]);if(n!=1)_exit(125);}
        // The app's threads block SIGPIPE, SIGQUIT and SIGUSR1, and a blocked mask survives exec:
        // the shell and its programs start with nothing blocked and default pipe and quit handling.
        sigset_t none;sigemptyset(&none);sigprocmask(SIG_SETMASK,&none,nullptr);
        signal(SIGPIPE,SIG_DFL);signal(SIGQUIT,SIG_DFL);
        if(chdir(dir.c_str())!=0)_exit(126); execve(exe.c_str(),argv.data(),envp.data());_exit(127);
    }
    if(defer)close(gate[0]);
    if(pid<0){close(fd);if(defer)close(gate[1]);return nullptr;}
    jint result[3]={fd,pid,gate[1]};auto array=env->NewIntArray(3);env->SetIntArrayRegion(array,0,3,result);return array;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_luftaquila_hometty_terminal_Native_read(JNIEnv* env,jobject,jint fd) {
    struct pollfd descriptor{fd,POLLIN,0};
    int ready;do{ready=poll(&descriptor,1,100);}while(ready<0 && errno==EINTR);
    if(ready==0)return env->NewByteArray(0);if(ready<0)return nullptr;
    char buffer[16384];ssize_t n; do { n=read(fd,buffer,sizeof(buffer)); }while(n<0 && errno==EINTR);
    if(n<=0)return nullptr;auto data=env->NewByteArray(n);env->SetByteArrayRegion(data,0,n,reinterpret_cast<jbyte*>(buffer));return data;
}
extern "C" JNIEXPORT void JNICALL Java_io_luftaquila_hometty_terminal_Native_write(JNIEnv* env,jobject,jint fd,jbyteArray bytes) {
    auto p=env->GetByteArrayElements(bytes,nullptr);int size=env->GetArrayLength(bytes);int done=0;
    while(done<size){auto n=write(fd,p+done,size-done);if(n<0 && errno==EINTR)continue;if(n<=0)break;done+=n;}env->ReleaseByteArrayElements(bytes,p,JNI_ABORT);
}
extern "C" JNIEXPORT jint JNICALL Java_io_luftaquila_hometty_terminal_Native_waitFor(JNIEnv*,jobject,jint pid) {
    int status;while(waitpid(pid,&status,0)<0){if(errno!=EINTR)return -1;}return WIFEXITED(status)?WEXITSTATUS(status):128+WTERMSIG(status);
}
extern "C" JNIEXPORT void JNICALL Java_io_luftaquila_hometty_terminal_Native_signal(JNIEnv*,jobject,jint fd,jint pid,jint sig) { int foreground=tcgetpgrp(fd);if(foreground>0 && foreground!=getpgrp() && foreground!=pid)kill(-foreground,sig);if(pid>0)kill(-pid,sig); }
extern "C" JNIEXPORT void JNICALL Java_io_luftaquila_hometty_terminal_Native_close(JNIEnv*,jobject,jint fd) { if(fd>=0)close(fd); }

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_luftaquila_hometty_terminal_Native_paste(JNIEnv* env,jobject,jlong ptr,jbyteArray text) {
    auto s=screen(ptr);auto data=env->GetByteArrayElements(text,nullptr);
    vterm_keyboard_start_paste(s->term);
    // Paste bytes are user text; encode bracketed-paste delimiters using the current terminal mode.
    size_t n=vterm_output_get_buffer_current(s->term);std::vector<char> bytes(n);
    vterm_output_read(s->term,bytes.data(),n);
    bytes.insert(bytes.end(),reinterpret_cast<char*>(data),reinterpret_cast<char*>(data)+env->GetArrayLength(text));
    vterm_keyboard_end_paste(s->term);n=vterm_output_get_buffer_current(s->term);size_t start=bytes.size();bytes.resize(start+n);vterm_output_read(s->term,bytes.data()+start,n);
    env->ReleaseByteArrayElements(text,data,JNI_ABORT);
    auto result=env->NewByteArray(bytes.size());env->SetByteArrayRegion(result,0,bytes.size(),reinterpret_cast<jbyte*>(bytes.data()));return result;
}

extern "C" int vterm_unicode_width(uint32_t codepoint);
extern "C" JNIEXPORT jint JNICALL Java_io_luftaquila_hometty_terminal_Native_characterWidth(JNIEnv*, jobject, jint cp) { return vterm_unicode_width(cp); }
