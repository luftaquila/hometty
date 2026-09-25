// App-private CLI transport. Only argv is serialized; no command-string interpolation.
// Frames from the launcher: 1 stdout, 2 stderr, 3 exit code, 4 shell code to exec in place.
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <cerrno>
#include <csignal>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
static bool transfer(int fd, void* data, size_t size, bool sending) {
    char* p=static_cast<char*>(data);
    while(size) { ssize_t n=sending ? write(fd,p,size) : read(fd,p,size);
        if(n<0 && errno==EINTR) continue; if(n<=0) return false; p+=n;size-=n; }
    return true;
}
static bool integer(int fd, unsigned value) { uint32_t n=htonl(value);return transfer(fd,&n,4,true); }
int main(int argc,char** argv) {
    // A launcher that closes early must give an error, not kill this process silently.
    signal(SIGPIPE,SIG_IGN);
    const char* socket=getenv("HOMETTY_SOCKET");
    if(!socket || strlen(socket)>100) { fputs("hometty: launcher connection unavailable\n",stderr);return 125; }
    int fd=::socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);if(fd<0)return 125;
    sockaddr_un address{};address.sun_family=AF_UNIX;memcpy(address.sun_path+1,socket,strlen(socket));
    if(connect(fd,reinterpret_cast<sockaddr*>(&address),offsetof(sockaddr_un,sun_path)+1+strlen(socket))<0) {
        perror("hometty: connect");close(fd);return 125;
    }
    const char* base=strrchr(argv[0],'/');base=base?base+1:argv[0];
    std::vector<std::string> args;
    if(strcmp(base,"hometty") && strcmp(base,"libhomettycli.so"))args.emplace_back(base);
    for(int i=1;i<argc;i++)args.emplace_back(argv[i]);
    if(args.empty())args.emplace_back("help");
    size_t total=0;for(auto& arg:args)total+=arg.size();
    if(args.size()>4096 || total>1048576) { fputs("hometty: argument list too long\n",stderr);return 125; }
    if(!integer(fd,args.size()))return 125;
    for(auto& arg:args)if(!integer(fd,arg.size()) || !transfer(fd,arg.data(),arg.size(),true)) {
        fputs("hometty: launcher connection closed\n",stderr);return 125;
    }
    while(true) {
        uint32_t header[2];if(!transfer(fd,header,8,false))return 125;
        unsigned type=ntohl(header[0]),size=ntohl(header[1]);
        if(type==3){close(fd);return size>255?125:size;}
        if((type!=1 && type!=2 && type!=4)||size>1048576)return 125;
        std::vector<char> data(size);if(!transfer(fd,data.data(),size,false))return 125;
        if(type==4) {
            // The launcher answered with shell code: the home shell (BusyBox ash) runs it, with
            // this command's own arguments as $0, $1... so they never become shell text.
            close(fd);
            const char* shell=getenv("HOMETTY_SHELL");
            if(!shell || shell[0]!='/') { fputs("hometty: shell unavailable\n",stderr);return 126; }
            // ENV, the home shell's setup, is gone once that shell ends; a job it left keeps running.
            std::string script="if [ -n \"$ENV\" ] && [ -f \"$ENV\" ]; then . \"$ENV\"; fi; ";script.append(data.data(),data.size());
            std::vector<char*> argvExec{const_cast<char*>("sh"),const_cast<char*>("-c"),script.data()};
            for(auto& arg:args)argvExec.push_back(arg.data());
            argvExec.push_back(nullptr);
            execv(shell,argvExec.data());perror("hometty: exec");return 126;
        }
        if(!transfer(type==1?STDOUT_FILENO:STDERR_FILENO,data.data(),size,true))return 125;
    }
}
